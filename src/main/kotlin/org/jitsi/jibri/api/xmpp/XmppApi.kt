/*
 * Copyright @ 2018 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.jitsi.jibri.api.xmpp

import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.JibriIq
import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.JibriIqProvider
import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.JibriStatusPacketExt
import org.jitsi.jibri.FileRecordingRequestParams
import org.jitsi.jibri.JibriManager
import org.jitsi.jibri.StartServiceResult
import org.jitsi.jibri.config.XmppEnvironmentConfig
import org.jitsi.jibri.health.EnvironmentContext
import org.jitsi.jibri.selenium.CallParams
import org.jitsi.jibri.service.JibriServiceStatus
import org.jitsi.jibri.service.JibriServiceStatusHandler
import org.jitsi.jibri.service.ServiceParams
import org.jitsi.jibri.service.impl.SipGatewayServiceParams
import org.jitsi.jibri.service.impl.StreamingParams
import org.jitsi.jibri.sipgateway.SipClientParams
import org.jitsi.jibri.util.NameableThreadFactory
import org.jitsi.jibri.util.extensions.error
import org.jitsi.jibri.util.getCallUrlInfoFromJid
import org.jitsi.xmpp.mucclient.MucClient
import org.jitsi.xmpp.mucclient.MucClientConfiguration
import org.jitsi.xmpp.mucclient.MucClientManager
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.XMPPError
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jxmpp.jid.BareJid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import java.util.concurrent.Executors
import java.util.logging.Logger

typealias MucClientProvider = (XMPPTCPConnectionConfiguration, String) -> MucClient

/**
 * [XmppApi] connects to XMPP MUCs according to the given [XmppEnvironmentConfig]s (which are
 * parsed from config.json) and listens for IQ messages which contain Jibri commands, which it relays
 * to the given [JibriManager].  The IQ messages are instances of [JibriIq] and allow the
 * starting and stopping of the services Jibri provides.
 * [XmppApi] subscribes to [JibriManager] status updates and translates those into
 * XMPP presence (defined by [JibriStatusPacketExt]) updates to advertise the status of this Jibri.
 * [XmppApi] takes care of translating the XMPP commands into the appropriate
 * [JibriManager] API calls and translates the results into XMPP IQ responses.
 */
class XmppApi (
    private val jibriManager: JibriManager,
    private val xmppConfigs: List<XmppEnvironmentConfig>
) {
    private val logger = Logger.getLogger(this::class.qualifiedName)
    private val executor = Executors.newSingleThreadExecutor(NameableThreadFactory("XmppApi"))

    /**
     * Start up the XMPP API by connecting and logging in to all the configured XMPP environments.  For each XMPP
     * connection, we'll listen for incoming [JibriIq] messages and handle them appropriately.  Join the MUC on
     * each connection and send an initial [JibriStatusPacketExt] presence.
     */
    fun start() {
        JibriStatusPacketExt.registerExtensionProvider()
        ProviderManager.addIQProvider(
            JibriIq.ELEMENT_NAME, JibriIq.NAMESPACE, JibriIqProvider()
        )

        val mucClientManager = MucClientManager(emptyArray<String>())

        mucClientManager.registerIQ(JibriIq())
        mucClientManager.setIQListener(::handleIq)

        val updatePresence: (JibriStatusPacketExt.Status) -> Unit = { status ->
            logger.info("Jibri reports its status is now $status, publishing presence to all connections.");
            val jibriStatus = JibriStatusPacketExt()
            jibriStatus.status = status
            mucClientManager.setPresenceExtension(jibriStatus)
        }
        jibriManager.addStatusHandler(updatePresence)

        // Create a list of MucClient configs based on this.xmppConfigs
        for (mucClientConfig in createMucClientConfigs()) {
            mucClientManager.addMucClient(mucClientConfig)
        }
    }

    private fun createMucClientConfigs(): List<MucClientConfiguration>
    {
        val mucClientConfigurations: MutableList<MucClientConfiguration> = mutableListOf()
        for (xmppEnvironmentConfig in xmppConfigs) {
            for (hostname in xmppEnvironmentConfig.xmppServerHosts) {
                val id = xmppEnvironmentConfig.name + "-" + hostname
                val mucClientConfig = MucClientConfiguration(id)

                mucClientConfig.hostname = hostname
                mucClientConfig.domain = xmppEnvironmentConfig.controlLogin.domain
                mucClientConfig.username = xmppEnvironmentConfig.controlLogin.username
                mucClientConfig.password = xmppEnvironmentConfig.controlLogin.password
                if (xmppEnvironmentConfig.trustAllXmppCerts) {
                    mucClientConfig.disableCertificateVerification = true
                }

                val mucJids: MutableList<String> = mutableListOf()
                val recordingMucJid = "${xmppEnvironmentConfig.controlMuc.roomName}@${xmppEnvironmentConfig.controlMuc.domain}"
                mucJids.add(recordingMucJid);
                if (xmppEnvironmentConfig.sipControlMuc != null){
                    mucJids.add("${xmppEnvironmentConfig.sipControlMuc.roomName}@${xmppEnvironmentConfig.sipControlMuc.domain}")
                }
                mucClientConfig.mucJids = mucJids

                if (mucClientConfig.isComplete) {
                    mucClientConfigurations.add(mucClientConfig)
                } else {
                    logger.error("Incomplete MucClientConfiguration")
                }
            }
        }
        return mucClientConfigurations
    }

    /**
     * Function to update outgoing [presence] stanza with current jibri status.
     */
    private fun updatePresenceStanza(presence: Presence) {
        val jibriStatus = JibriStatusPacketExt()
        jibriStatus.status =
                if (jibriManager.busy()) JibriStatusPacketExt.Status.BUSY
                else JibriStatusPacketExt.Status.IDLE
        presence.addExtension(jibriStatus)
    }

    private fun handleIq(iq: IQ, mucClient: MucClient): IQ {
        logger.info("Received an IQ ${iq.toXML()}.");
        return if (iq is JibriIq) {
            when (iq.action) {
                JibriIq.Action.START -> handleStartJibriIq(iq, findXmppEnvironment(mucClient), mucClient)
                JibriIq.Action.STOP -> handleStopJibriIq(iq)
                else -> IQ.createErrorResponse(
                        iq,
                        XMPPError.getBuilder().setCondition(XMPPError.Condition.bad_request))
            }
        } else {
            IQ.createErrorResponse(
                    iq,
                    XMPPError.getBuilder().setCondition(XMPPError.Condition.bad_request))
        }
    }

    private fun findXmppEnvironment(mucClient: MucClient): XmppEnvironmentConfig? {
        for (xmppEnvironmentConfig in xmppConfigs) {
            for (hostname in xmppEnvironmentConfig.xmppServerHosts) {
                val id = xmppEnvironmentConfig.name + "-" + hostname
                if (id == mucClient.getId()) {
                    return xmppEnvironmentConfig
                }
            }
        }

        return null
    }

    /**
     * Handle a start [JibriIq] message.  We'll respond immediately with a [JibriIq.Status.PENDING] IQ response and
     * send a new IQ with the subsequent stats after starting the service:
     * [JibriIq.Status.OFF] if there was an error starting the service (or an error while the service was running).
     *  In this case, a [JibriIq.FailureReason] will be set as well.
     * [JibriIq.Status.ON] if the service started successfully
     */
    private fun handleStartJibriIq(
        startJibriIq: JibriIq,
        xmppEnvironment: XmppEnvironmentConfig,
        mucClient: MucClient
    ): IQ {
        logger.info("Received start request")
        // We don't want to block the response to wait for the service to actually start, so submit a job to
        // start the service asynchronously and send an IQ with the status after its done.
        executor.submit {
            val resultIq = JibriIqHelper.create(startJibriIq.from)
            resultIq.sipAddress = startJibriIq.sipAddress
            try {
                logger.info("Starting service")

                // If there is an issue with the service while it's running, we need to send an IQ
                // to notify the caller who invoked the service of its status, so we'll listen
                // for the service's status while it's running and this method will be invoked
                // if it changes
                val serviceStatusHandler = createServiceStatusHandler(startJibriIq, mucClient)
                val startServiceResult = handleStartService(startJibriIq, xmppEnvironment, serviceStatusHandler)

                when (startServiceResult) {
                    StartServiceResult.SUCCESS -> {
                        resultIq.status = JibriIq.Status.ON
                    }
                    StartServiceResult.BUSY -> {
                        resultIq.status = JibriIq.Status.OFF
                        resultIq.failureReason = JibriIq.FailureReason.BUSY
                    }
                    StartServiceResult.ERROR -> {
                        resultIq.status = JibriIq.Status.OFF
                        resultIq.failureReason = JibriIq.FailureReason.ERROR
                    }
                }
            } catch (e: Throwable) {
                logger.error("Error in startService task", e)
                resultIq.status = JibriIq.Status.OFF
                resultIq.failureReason = JibriIq.FailureReason.ERROR
            } finally {
                logger.info("Sending start service response iq: ${resultIq.toXML()}")
                mucClient.sendStanza(resultIq)
            }
        }
        // Immediately respond that the request is pending
        val initialResponse = JibriIqHelper.createResult(startJibriIq, JibriIq.Status.PENDING)
        logger.info("Sending 'pending' response to start IQ")
        return initialResponse
    }

    private fun createServiceStatusHandler(request: JibriIq, mucClient: MucClient): (JibriServiceStatus) -> Unit {
        return { serviceStatus ->
            when (serviceStatus) {
                JibriServiceStatus.ERROR -> {
                    with(JibriIqHelper.create(request.from, status = JibriIq.Status.OFF)) {
                        failureReason = JibriIq.FailureReason.ERROR
                        sipAddress = request.sipAddress
                        logger.info("Current service had an error, sending error iq ${toXML()}")
                        mucClient.sendStanza(this)
                    }
                }
                JibriServiceStatus.FINISHED -> {
                    with(JibriIqHelper.create(request.from, status = JibriIq.Status.OFF)) {
                        sipAddress = request.sipAddress
                        logger.info("Current service finished, sending off iq ${toXML()}")
                        mucClient.sendStanza(this)
                    }
                }
            }
        }
    }

    /**
     * Handle a stop [JibriIq] message to stop the currently running service (if there is one).  Send a [JibriIq]
     * response with [JibriIq.Status.OFF].
     */
    private fun handleStopJibriIq(stopJibriIq: JibriIq): IQ {
        jibriManager.stopService()
        // By this point the service has been fully stopped
        return JibriIqHelper.createResult(stopJibriIq, JibriIq.Status.OFF)
    }

    /**
     * Helper function to actually start the service.  We need to parse the fields in the [JibriIq] message
     * to determine which [JibriManager] service API to call, as well as convert the types into what [JibriManager]
     * expects
     */
    private fun handleStartService(
        startIq: JibriIq,
        xmppEnvironment: XmppEnvironmentConfig,
        serviceStatusHandler: JibriServiceStatusHandler
    ): StartServiceResult {
        val callUrlInfo = getCallUrlInfoFromJid(
            startIq.room,
            xmppEnvironment.stripFromRoomDomain,
            xmppEnvironment.xmppDomain
        )
        val serviceParams = ServiceParams(xmppEnvironment.usageTimeoutMins)
        val callParams = CallParams(callUrlInfo)
        logger.info("Parsed call url info: $callUrlInfo")
        return when (startIq.mode()) {
            JibriMode.FILE -> {
                jibriManager.startFileRecording(
                    serviceParams,
                    FileRecordingRequestParams(callParams, startIq.sessionId, xmppEnvironment.callLogin),
                    EnvironmentContext(xmppEnvironment.name),
                    serviceStatusHandler
                )
            }
            JibriMode.STREAM -> {
                jibriManager.startStreaming(
                    serviceParams,
                    StreamingParams(
                        callParams,
                        startIq.sessionId,
                        xmppEnvironment.callLogin,
                        youTubeStreamKey = startIq.streamId,
                        youTubeBroadcastId = startIq.youtubeBroadcastId),
                    EnvironmentContext(xmppEnvironment.name),
                    serviceStatusHandler
                )
            }
            JibriMode.SIPGW -> {
                jibriManager.startSipGateway(
                    serviceParams,
                    SipGatewayServiceParams(callParams, SipClientParams(startIq.sipAddress, startIq.displayName)),
                    EnvironmentContext(xmppEnvironment.name),
                    serviceStatusHandler
                )
            }
            else -> {
                StartServiceResult.ERROR
            }
        }
    }
}
