/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.connectivity;

import static android.net.NetworkCapabilities.MAX_TRANSPORT;
import static android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI_AWARE;

import android.net.ConnectivityMetricsEvent;
import android.net.metrics.ApfProgramEvent;
import android.net.metrics.ApfStats;
import android.net.metrics.DefaultNetworkEvent;
import android.net.metrics.DhcpClientEvent;
import android.net.metrics.DhcpErrorEvent;
import android.net.metrics.DnsEvent;
import android.net.metrics.ConnectStats;
import android.net.metrics.IpManagerEvent;
import android.net.metrics.IpReachabilityEvent;
import android.net.metrics.NetworkEvent;
import android.net.metrics.RaEvent;
import android.net.metrics.ValidationProbeEvent;
import android.os.Parcelable;
import android.util.SparseArray;
import android.util.SparseIntArray;
import com.android.server.connectivity.metrics.nano.IpConnectivityLogClass;
import com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.IpConnectivityEvent;
import com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.IpConnectivityLog;
import com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.NetworkId;
import com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.Pair;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


/** {@hide} */
final public class IpConnectivityEventBuilder {
    private IpConnectivityEventBuilder() {
    }

    public static byte[] serialize(int dropped, List<IpConnectivityEvent> events)
            throws IOException {
        final IpConnectivityLog log = new IpConnectivityLog();
        log.events = events.toArray(new IpConnectivityEvent[events.size()]);
        log.droppedEvents = dropped;
        if ((log.events.length > 0) || (dropped > 0)) {
            // Only write version number if log has some information at all.
            log.version = IpConnectivityMetrics.VERSION;
        }
        return IpConnectivityLog.toByteArray(log);
    }

    public static List<IpConnectivityEvent> toProto(List<ConnectivityMetricsEvent> eventsIn) {
        final ArrayList<IpConnectivityEvent> eventsOut = new ArrayList<>(eventsIn.size());
        for (ConnectivityMetricsEvent in : eventsIn) {
            final IpConnectivityEvent out = toProto(in);
            if (out == null) {
                continue;
            }
            eventsOut.add(out);
        }
        return eventsOut;
    }

    public static IpConnectivityEvent toProto(ConnectivityMetricsEvent ev) {
        final IpConnectivityEvent out = buildEvent(ev.netId, ev.transports, ev.ifname);
        out.timeMs = ev.timestamp;
        if (!setEvent(out, ev.data)) {
            return null;
        }
        return out;
    }

    public static IpConnectivityEvent toProto(ConnectStats in) {
        IpConnectivityLogClass.ConnectStatistics stats =
                new IpConnectivityLogClass.ConnectStatistics();
        stats.connectCount = in.connectCount;
        stats.connectBlockingCount = in.connectBlockingCount;
        stats.ipv6AddrCount = in.ipv6ConnectCount;
        stats.latenciesMs = in.latencies.toArray();
        stats.errnosCounters = toPairArray(in.errnos);
        final IpConnectivityEvent out = buildEvent(in.netId, in.transports, null);
        out.setConnectStatistics(stats);
        return out;
    }


    public static IpConnectivityEvent toProto(DnsEvent in) {
        IpConnectivityLogClass.DNSLookupBatch dnsLookupBatch =
                new IpConnectivityLogClass.DNSLookupBatch();
        in.resize(in.eventCount);
        dnsLookupBatch.eventTypes = bytesToInts(in.eventTypes);
        dnsLookupBatch.returnCodes = bytesToInts(in.returnCodes);
        dnsLookupBatch.latenciesMs = in.latenciesMs;
        final IpConnectivityEvent out = buildEvent(in.netId, in.transports, null);
        out.setDnsLookupBatch(dnsLookupBatch);
        return out;
    }

    private static IpConnectivityEvent buildEvent(int netId, long transports, String ifname) {
        final IpConnectivityEvent ev = new IpConnectivityEvent();
        ev.networkId = netId;
        ev.transports = transports;
        if (ifname != null) {
            ev.ifName = ifname;
        }
        inferLinkLayer(ev);
        return ev;
    }

    private static boolean setEvent(IpConnectivityEvent out, Parcelable in) {
        if (in instanceof DhcpErrorEvent) {
            setDhcpErrorEvent(out, (DhcpErrorEvent) in);
            return true;
        }

        if (in instanceof DhcpClientEvent) {
            setDhcpClientEvent(out, (DhcpClientEvent) in);
            return true;
        }

        if (in instanceof IpManagerEvent) {
            setIpManagerEvent(out, (IpManagerEvent) in);
            return true;
        }

        if (in instanceof IpReachabilityEvent) {
            setIpReachabilityEvent(out, (IpReachabilityEvent) in);
            return true;
        }

        if (in instanceof DefaultNetworkEvent) {
            setDefaultNetworkEvent(out, (DefaultNetworkEvent) in);
            return true;
        }

        if (in instanceof NetworkEvent) {
            setNetworkEvent(out, (NetworkEvent) in);
            return true;
        }

        if (in instanceof ValidationProbeEvent) {
            setValidationProbeEvent(out, (ValidationProbeEvent) in);
            return true;
        }

        if (in instanceof ApfProgramEvent) {
            setApfProgramEvent(out, (ApfProgramEvent) in);
            return true;
        }

        if (in instanceof ApfStats) {
            setApfStats(out, (ApfStats) in);
            return true;
        }

        if (in instanceof RaEvent) {
            setRaEvent(out, (RaEvent) in);
            return true;
        }

        return false;
    }

    private static void setDhcpErrorEvent(IpConnectivityEvent out, DhcpErrorEvent in) {
        IpConnectivityLogClass.DHCPEvent dhcpEvent = new IpConnectivityLogClass.DHCPEvent();
        dhcpEvent.setErrorCode(in.errorCode);
        out.setDhcpEvent(dhcpEvent);
    }

    private static void setDhcpClientEvent(IpConnectivityEvent out, DhcpClientEvent in) {
        IpConnectivityLogClass.DHCPEvent dhcpEvent = new IpConnectivityLogClass.DHCPEvent();
        dhcpEvent.setStateTransition(in.msg);
        dhcpEvent.durationMs = in.durationMs;
        out.setDhcpEvent(dhcpEvent);
    }

    private static void setIpManagerEvent(IpConnectivityEvent out, IpManagerEvent in) {
        IpConnectivityLogClass.IpProvisioningEvent ipProvisioningEvent =
                new IpConnectivityLogClass.IpProvisioningEvent();
        ipProvisioningEvent.eventType = in.eventType;
        ipProvisioningEvent.latencyMs = (int) in.durationMs;
        out.setIpProvisioningEvent(ipProvisioningEvent);
    }

    private static void setIpReachabilityEvent(IpConnectivityEvent out, IpReachabilityEvent in) {
        IpConnectivityLogClass.IpReachabilityEvent ipReachabilityEvent =
                new IpConnectivityLogClass.IpReachabilityEvent();
        ipReachabilityEvent.eventType = in.eventType;
        out.setIpReachabilityEvent(ipReachabilityEvent);
    }

    private static void setDefaultNetworkEvent(IpConnectivityEvent out, DefaultNetworkEvent in) {
        IpConnectivityLogClass.DefaultNetworkEvent defaultNetworkEvent =
                new IpConnectivityLogClass.DefaultNetworkEvent();
        defaultNetworkEvent.networkId = netIdOf(in.netId);
        defaultNetworkEvent.previousNetworkId = netIdOf(in.prevNetId);
        defaultNetworkEvent.transportTypes = in.transportTypes;
        defaultNetworkEvent.previousNetworkIpSupport = ipSupportOf(in);
        out.setDefaultNetworkEvent(defaultNetworkEvent);
    }

    private static void setNetworkEvent(IpConnectivityEvent out, NetworkEvent in) {
        IpConnectivityLogClass.NetworkEvent networkEvent =
                new IpConnectivityLogClass.NetworkEvent();
        networkEvent.networkId = netIdOf(in.netId);
        networkEvent.eventType = in.eventType;
        networkEvent.latencyMs = (int) in.durationMs;
        out.setNetworkEvent(networkEvent);
    }

    private static void setValidationProbeEvent(IpConnectivityEvent out, ValidationProbeEvent in) {
        IpConnectivityLogClass.ValidationProbeEvent validationProbeEvent =
                new IpConnectivityLogClass.ValidationProbeEvent();
        validationProbeEvent.latencyMs = (int) in.durationMs;
        validationProbeEvent.probeType = in.probeType;
        validationProbeEvent.probeResult = in.returnCode;
        out.setValidationProbeEvent(validationProbeEvent);
    }

    private static void setApfProgramEvent(IpConnectivityEvent out, ApfProgramEvent in) {
        IpConnectivityLogClass.ApfProgramEvent apfProgramEvent =
                new IpConnectivityLogClass.ApfProgramEvent();
        apfProgramEvent.lifetime = in.lifetime;
        apfProgramEvent.effectiveLifetime = in.actualLifetime;
        apfProgramEvent.filteredRas = in.filteredRas;
        apfProgramEvent.currentRas = in.currentRas;
        apfProgramEvent.programLength = in.programLength;
        if (isBitSet(in.flags, ApfProgramEvent.FLAG_MULTICAST_FILTER_ON)) {
            apfProgramEvent.dropMulticast = true;
        }
        if (isBitSet(in.flags, ApfProgramEvent.FLAG_HAS_IPV4_ADDRESS)) {
            apfProgramEvent.hasIpv4Addr = true;
        }
        out.setApfProgramEvent(apfProgramEvent);
    }

    private static void setApfStats(IpConnectivityEvent out, ApfStats in) {
        IpConnectivityLogClass.ApfStatistics apfStatistics =
                new IpConnectivityLogClass.ApfStatistics();
        apfStatistics.durationMs = in.durationMs;
        apfStatistics.receivedRas = in.receivedRas;
        apfStatistics.matchingRas = in.matchingRas;
        apfStatistics.droppedRas = in.droppedRas;
        apfStatistics.zeroLifetimeRas = in.zeroLifetimeRas;
        apfStatistics.parseErrors = in.parseErrors;
        apfStatistics.programUpdates = in.programUpdates;
        apfStatistics.programUpdatesAll = in.programUpdatesAll;
        apfStatistics.programUpdatesAllowingMulticast = in.programUpdatesAllowingMulticast;
        apfStatistics.maxProgramSize = in.maxProgramSize;
        out.setApfStatistics(apfStatistics);
    }

    private static void setRaEvent(IpConnectivityEvent out, RaEvent in) {
        IpConnectivityLogClass.RaEvent raEvent = new IpConnectivityLogClass.RaEvent();
        raEvent.routerLifetime = in.routerLifetime;
        raEvent.prefixValidLifetime = in.prefixValidLifetime;
        raEvent.prefixPreferredLifetime = in.prefixPreferredLifetime;
        raEvent.routeInfoLifetime = in.routeInfoLifetime;
        raEvent.rdnssLifetime = in.rdnssLifetime;
        raEvent.dnsslLifetime = in.dnsslLifetime;
        out.setRaEvent(raEvent);
    }

    private static int[] bytesToInts(byte[] in) {
        final int[] out = new int[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[i] & 0xFF;
        }
        return out;
    }

    private static Pair[] toPairArray(SparseIntArray counts) {
        final int s = counts.size();
        Pair[] pairs = new Pair[s];
        for (int i = 0; i < s; i++) {
            Pair p = new Pair();
            p.key = counts.keyAt(i);
            p.value = counts.valueAt(i);
            pairs[i] = p;
        }
        return pairs;
    }

    private static NetworkId netIdOf(int netid) {
        final NetworkId ni = new NetworkId();
        ni.networkId = netid;
        return ni;
    }

    private static int ipSupportOf(DefaultNetworkEvent in) {
        if (in.prevIPv4 && in.prevIPv6) {
            return IpConnectivityLogClass.DefaultNetworkEvent.DUAL;
        }
        if (in.prevIPv6) {
            return IpConnectivityLogClass.DefaultNetworkEvent.IPV6;
        }
        if (in.prevIPv4) {
            return IpConnectivityLogClass.DefaultNetworkEvent.IPV4;
        }
        return IpConnectivityLogClass.DefaultNetworkEvent.NONE;
    }

    private static boolean isBitSet(int flags, int bit) {
        return (flags & (1 << bit)) != 0;
    }

    private static void inferLinkLayer(IpConnectivityEvent ev) {
        int linkLayer = IpConnectivityLogClass.UNKNOWN;
        if (ev.transports != 0) {
            linkLayer = transportsToLinkLayer(ev.transports);
        } else if (ev.ifName != null) {
            linkLayer = ifnameToLinkLayer(ev.ifName);
        }
        if (linkLayer == IpConnectivityLogClass.UNKNOWN) {
            return;
        }
        ev.linkLayer = linkLayer;
        ev.ifName = "";
    }

    private static int transportsToLinkLayer(long transports) {
        switch (Long.bitCount(transports)) {
            case 0:
                return IpConnectivityLogClass.UNKNOWN;
            case 1:
                int t = Long.numberOfTrailingZeros(transports);
                return transportToLinkLayer(t);
            default:
                return IpConnectivityLogClass.MULTIPLE;
        }
    }

    private static int transportToLinkLayer(int transport) {
        if (0 <= transport && transport < TRANSPORT_LINKLAYER_MAP.length) {
            return TRANSPORT_LINKLAYER_MAP[transport];
        }
        return IpConnectivityLogClass.UNKNOWN;
    }

    private static final int[] TRANSPORT_LINKLAYER_MAP = new int[MAX_TRANSPORT + 1];
    static {
        TRANSPORT_LINKLAYER_MAP[TRANSPORT_CELLULAR]   = IpConnectivityLogClass.CELLULAR;
        TRANSPORT_LINKLAYER_MAP[TRANSPORT_WIFI]       = IpConnectivityLogClass.WIFI;
        TRANSPORT_LINKLAYER_MAP[TRANSPORT_BLUETOOTH]  = IpConnectivityLogClass.BLUETOOTH;
        TRANSPORT_LINKLAYER_MAP[TRANSPORT_ETHERNET]   = IpConnectivityLogClass.ETHERNET;
        TRANSPORT_LINKLAYER_MAP[TRANSPORT_VPN]        = IpConnectivityLogClass.UNKNOWN;
        // TODO: change mapping TRANSPORT_WIFI_AWARE -> WIFI_AWARE
        TRANSPORT_LINKLAYER_MAP[TRANSPORT_WIFI_AWARE] = IpConnectivityLogClass.UNKNOWN;
    };

    private static int ifnameToLinkLayer(String ifname) {
        // Do not try to catch all interface names with regexes, instead only catch patterns that
        // are cheap to check, and otherwise fallback on postprocessing in aggregation layer.
        for (int i = 0; i < IFNAME_LINKLAYER_MAP.size(); i++) {
            String pattern = IFNAME_LINKLAYER_MAP.valueAt(i);
            if (ifname.startsWith(pattern)) {
                return IFNAME_LINKLAYER_MAP.keyAt(i);
            }
        }
        return IpConnectivityLogClass.UNKNOWN;
    }

    private static final SparseArray<String> IFNAME_LINKLAYER_MAP = new SparseArray<String>();
    static {
        IFNAME_LINKLAYER_MAP.put(IpConnectivityLogClass.CELLULAR, "rmnet");
        IFNAME_LINKLAYER_MAP.put(IpConnectivityLogClass.WIFI, "wlan");
        IFNAME_LINKLAYER_MAP.put(IpConnectivityLogClass.BLUETOOTH, "bt-pan");
        // TODO: rekey to USB
        IFNAME_LINKLAYER_MAP.put(IpConnectivityLogClass.ETHERNET, "usb");
        // TODO: add mappings for nan -> WIFI_AWARE and p2p -> WIFI_P2P
    }
}
