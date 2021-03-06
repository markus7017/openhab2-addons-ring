/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.ring.handler;

import static org.openhab.binding.ring.RingBindingConstants.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.library.types.DateTimeType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.net.NetworkAddressService;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.json.simple.parser.ParseException;
import org.openhab.binding.ring.internal.RestClient;
import org.openhab.binding.ring.internal.RingAccount;
import org.openhab.binding.ring.internal.RingDeviceRegistry;
import org.openhab.binding.ring.internal.data.DataFactory;
import org.openhab.binding.ring.internal.data.Profile;
import org.openhab.binding.ring.internal.data.RingDevices;
import org.openhab.binding.ring.internal.data.RingEvent;
import org.openhab.binding.ring.internal.errors.AuthenticationException;
import org.openhab.binding.ring.internal.errors.DuplicateIdException;

/**
 * The {@link RingDoorbellHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Wim Vissers - Initial contribution
 */
public class AccountHandler extends AbstractRingHandler implements RingAccount {

    private // Scheduler
    ScheduledFuture<?> jobTokenRefresh = null;
    private Runnable runnableToken = null;

    /**
     * The user profile retrieved when authenticating.
     */
    private Profile userProfile;
    /**
     * The registry.
     */
    private RingDeviceRegistry registry;
    /**
     * The RestClient is used to connect to the Ring Account.
     */
    private RestClient restClient;
    /**
     * The list with events.
     */
    private List<RingEvent> lastEvents;
    /**
     * The index to the current event.
     */
    private int eventIndex;

    private NetworkAddressService networkAddressService;

    public AccountHandler(Thing thing, NetworkAddressService networkAddressService) {
        super(thing);
        this.networkAddressService = networkAddressService;
        eventIndex = 0;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            boolean eventListOk = lastEvents != null && lastEvents.size() > eventIndex;
            switch (channelUID.getId()) {
                case CHANNEL_EVENT_URL:
                    if (eventListOk) {
                        updateState(channelUID,
                                new StringType(DataFactory.getDingVideoUrl(userProfile, lastEvents.get(eventIndex))));
                    }
                    break;
                case CHANNEL_EVENT_CREATED_AT:
                    if (eventListOk) {
                        updateState(channelUID, new DateTimeType(lastEvents.get(eventIndex).getCreatedAt()));
                    }
                    break;
                case CHANNEL_EVENT_KIND:
                    if (eventListOk) {
                        updateState(channelUID, new StringType(lastEvents.get(eventIndex).getKind()));
                    }
                    break;
                case CHANNEL_EVENT_DOORBOT_ID:
                    if (eventListOk) {
                        updateState(channelUID, new StringType(lastEvents.get(eventIndex).getDoorbot().getId()));
                    }
                    break;
                case CHANNEL_EVENT_DOORBOT_DESCRIPTION:
                    if (eventListOk) {
                        updateState(channelUID,
                                new StringType(lastEvents.get(eventIndex).getDoorbot().getDescription()));
                    }
                    break;
                case CHANNEL_CONTROL_STATUS:
                    updateState(channelUID, status);
                    break;
                case CHANNEL_CONTROL_ENABLED:
                    updateState(channelUID, enabled);
                    break;
                default:
                    logger.debug("Command received for an unknown channel: {}", channelUID.getId());
                    break;
            }
            refreshState();
        } else if (command instanceof OnOffType) {
            OnOffType xcommand = (OnOffType) command;
            switch (channelUID.getId()) {
                case CHANNEL_CONTROL_STATUS:
                    status = xcommand;
                    updateState(channelUID, status);
                    break;
                case CHANNEL_CONTROL_ENABLED:
                    if (!enabled.equals(xcommand)) {
                        enabled = xcommand;
                        updateState(channelUID, enabled);
                        if (enabled.equals(OnOffType.ON)) {
                            Configuration config = getThing().getConfiguration();
                            Integer refreshInterval = ((BigInteger) config.get("refreshInterval")).intValue();
                            ;
                            startAutomaticRefresh(refreshInterval);
                        } else {
                            stopAutomaticRefresh();
                        }
                    }
                    break;
                default:
                    logger.debug("Command received for an unknown channel: {}", channelUID.getId());
                    break;
            }
        } else {
            logger.debug("Command {} is not supported for channel: {}", command, channelUID.getId());
        }
    }

    /**
     * Refresh the state of channels that may have changed by (re-)initialization.
     */
    @Override
    protected void refreshState() {
    }

    @Override
    public void initialize() {
        logger.debug("Initializing Ring Account handler.");
        super.initialize();

        Configuration config = getThing().getConfiguration();
        Integer refreshInterval = ((BigDecimal) config.get("refreshInterval")).intValueExact();
        String username = (String) config.get("username");
        String password = (String) config.get("password");
        String hardwareId = (String) config.get("hardwareId");

        try {
            if (hardwareId.isEmpty()) {
                hardwareId = getLocalMAC();
                if ((hardwareId == null) || hardwareId.isEmpty()) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Hardware ID missing, check thing config");
                    return;
                }
                // write hardwareId to thing config
                config.remove("hardwareId");
                config.put("hardwareId", hardwareId);
                updateConfiguration(config);
            }

            restClient = new RestClient();
            userProfile = restClient.getAuthenticatedProfile(username, password, hardwareId);

            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING, "Retrieving device list");
        } catch (AuthenticationException ex) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Invalid credentials.");
        } catch (ParseException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Invalid response from api.ring.com.");
        } catch (Exception e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Initialization failed: " + e.getMessage());
        }

        // Note: When initialization can NOT be done set the status with more details for further
        // analysis. See also class ThingStatusDetail for all available status details.
        // Add a description to give user information to understand why thing does not work
        // as expected. E.g.
        // updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
        // "Can not access device as username and/or password are invalid");
        startAutomaticRefresh(refreshInterval);
        startSessionRefresh(300);
    }

    @Override
    protected void minuteTick() {
        if (registry == null) {
            try {
                // Init the devices
                RingDevices ringDevices = restClient.getRingDevices(userProfile, this);
                registry = RingDeviceRegistry.getInstance();
                registry.addRingDevices(ringDevices.getRingDevices());
                updateStatus(ThingStatus.ONLINE);
            } catch (AuthenticationException | ParseException e) {
                Configuration config = getThing().getConfiguration();
                String username = (String) config.get("username");
                String password = (String) config.get("password");
                String hardwareId = (String) config.get("hardwareId");
                try {
                    // restClient = new RestClient();
                    userProfile = restClient.getAuthenticatedProfile(username, password, hardwareId);

                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                            "Retrieving device list");
                } catch (AuthenticationException ex) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Invalid credentials.");
                } catch (ParseException e1) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Invalid response from api.ring.com.");
                } finally {
                    try {
                        RingDevices ringDevices = restClient.getRingDevices(userProfile, this);
                        registry = RingDeviceRegistry.getInstance();
                        registry.addRingDevices(ringDevices.getRingDevices());
                        updateStatus(ThingStatus.ONLINE);
                    } catch (DuplicateIdException | AuthenticationException | ParseException e11) {
                        registry = null;
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                "Invalid response from ring.com.");
                    }
                }
            } catch (DuplicateIdException ignored) {
                updateStatus(ThingStatus.ONLINE);
            }
        } else {
            // Update the events
            try {
                // restClient.refresh_session(userProfile.getRefreshToken());

                String id = lastEvents == null || lastEvents.isEmpty() ? "?" : lastEvents.get(0).getEventId();
                lastEvents = restClient.getHistory(userProfile, 5);
                if (lastEvents != null && !lastEvents.isEmpty() && !lastEvents.get(0).getEventId().equals(id)) {
                    handleCommand(new ChannelUID(thing.getUID(), CHANNEL_EVENT_URL), RefreshType.REFRESH);
                    handleCommand(new ChannelUID(thing.getUID(), CHANNEL_EVENT_CREATED_AT), RefreshType.REFRESH);
                    handleCommand(new ChannelUID(thing.getUID(), CHANNEL_EVENT_KIND), RefreshType.REFRESH);
                    handleCommand(new ChannelUID(thing.getUID(), CHANNEL_EVENT_DOORBOT_ID), RefreshType.REFRESH);
                    handleCommand(new ChannelUID(thing.getUID(), CHANNEL_EVENT_DOORBOT_DESCRIPTION),
                            RefreshType.REFRESH);
                }
            } catch (ParseException | AuthenticationException ex) {
                registry = null;
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Invalid response from ring.com.");
            }
        }
    }

    /**
     * Check every 60 seconds if one of the alarm times is reached.
     */
    protected void startSessionRefresh(int refreshInterval) {
        runnableToken = new Runnable() {
            @Override
            public void run() {
                try {
                    if (restClient != null) {
                        restClient.refresh_session(userProfile.getRefreshToken());
                    }
                } catch (Exception e) {
                    logger.debug("SessionRefresh: Exception occurred during execution: {}", e.getMessage(), e);
                }
            }
        };

        jobTokenRefresh = scheduler.scheduleAtFixedRate(runnableToken, 90, refreshInterval, TimeUnit.SECONDS);
    }

    protected void stopSessionRefresh() {
        if (jobTokenRefresh != null) {
            jobTokenRefresh.cancel(true);
            jobTokenRefresh = null;
        }
    }

    String getLocalMAC() throws Exception {
        // get local ip from OH system settings
        String localIP = networkAddressService.getPrimaryIpv4HostAddress();
        if ((localIP == null) || (localIP.isEmpty())) {
            logger.info("No local IP selected in openHAB system configuration");
            return "";
        }

        // get MAC address
        InetAddress ip = InetAddress.getByName(localIP);
        NetworkInterface network = NetworkInterface.getByInetAddress(ip);
        if (network != null) {
            byte[] mac = network.getHardwareAddress();

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mac.length; i++) {
                sb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? "-" : ""));
            }
            String localMAC = sb.toString();
            logger.info("Local IP address='{}', local MAC address = '{}'", localIP, localMAC);
            return localMAC;
        }
        return "";
    }

    @Override
    public RestClient getRestClient() {
        return restClient;
    }

    @Override
    public Profile getProfile() {
        return userProfile;
    }

    /**
     * Dispose off the refreshJob nicely.
     */
    @Override
    public void dispose() {
        stopSessionRefresh();
        super.dispose();
    }
}
