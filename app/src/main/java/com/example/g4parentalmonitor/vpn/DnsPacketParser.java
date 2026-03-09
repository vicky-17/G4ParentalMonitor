package com.example.g4parentalmonitor.vpn;

import android.util.Log;

/**
 * DnsPacketParser
 *
 * Parses raw IPv4 + UDP + DNS packets read from the VPN tunnel FileDescriptor.
 * Also builds synthesized DNS responses to write back into the tunnel.
 *
 * Packet layout (all offsets from byte 0):
 *   [0..19]  IPv4 header  (20 bytes, no options assumed)
 *   [20..27] UDP  header  (8 bytes)
 *   [28..]   DNS  payload
 */
public class DnsPacketParser {

    private static final String TAG = "DnsPacketParser";

    // ── Public data class ─────────────────────────────────────────────────────

    public static class DnsQuery {
        public String domain;
        public int    queryType;        // 1=A, 28=AAAA, …
        public int    transactionId;    // 2 bytes, for matching response

        // Source / dest addresses & ports (needed to build the reply IP header)
        public byte[] srcIp;   // 4 bytes
        public byte[] dstIp;   // 4 bytes
        public int    srcPort;
        public int    dstPort;

        // Pointers into the original buffer (used for upstream forwarding)
        public byte[] rawPacket;
        public int    rawLength;
        public int    dnsPayloadOffset = 28;
    }

    // ── Parse ─────────────────────────────────────────────────────────────────

    public static DnsQuery parse(byte[] buf, int len) {
        try {
            if (len < 28) return null;

            // IPv4 version check
            int version = (buf[0] >> 4) & 0xF;
            if (version != 4) return null;

            // Protocol must be UDP (17)
            int protocol = buf[9] & 0xFF;
            if (protocol != 17) return null;

            // Destination UDP port must be 53 (DNS)
            int dstPort = ((buf[22] & 0xFF) << 8) | (buf[23] & 0xFF);
            if (dstPort != 53) return null;

            if (len < 40) return null; // At minimum need DNS header

            DnsQuery q = new DnsQuery();
            q.rawPacket        = buf.clone();
            q.rawLength        = len;
            q.dnsPayloadOffset = 28;

            // Source / dest IPs
            q.srcIp = new byte[]{ buf[12], buf[13], buf[14], buf[15] };
            q.dstIp = new byte[]{ buf[16], buf[17], buf[18], buf[19] };

            // Ports
            q.srcPort = ((buf[20] & 0xFF) << 8) | (buf[21] & 0xFF);
            q.dstPort = dstPort;

            // DNS header starts at offset 28
            q.transactionId = ((buf[28] & 0xFF) << 8) | (buf[29] & 0xFF);

            // QR bit (bit 15 of flags): 0 = query, 1 = response
            int flags = ((buf[30] & 0xFF) << 8) | (buf[31] & 0xFF);
            if ((flags & 0x8000) != 0) return null; // It's a response — ignore

            // Question count
            int qdCount = ((buf[32] & 0xFF) << 8) | (buf[33] & 0xFF);
            if (qdCount == 0) return null;

            // Parse domain name from QNAME (starts at offset 40)
            int offset = 40;
            StringBuilder sb = new StringBuilder();
            while (offset < len) {
                int labelLen = buf[offset] & 0xFF;
                if (labelLen == 0) { offset++; break; }
                if (sb.length() > 0) sb.append('.');
                offset++;
                for (int i = 0; i < labelLen && offset < len; i++, offset++) {
                    sb.append((char)(buf[offset] & 0xFF));
                }
            }
            q.domain = sb.toString();

            // QTYPE (2 bytes after QNAME null terminator)
            if (offset + 1 < len) {
                q.queryType = ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF);
            } else {
                q.queryType = 1; // Default to A
            }

            return q;
        } catch (Exception e) {
            Log.v(TAG, "Parse error: " + e.getMessage());
            return null;
        }
    }

    // ── Response builders ─────────────────────────────────────────────────────

    /** Build a DNS NXDOMAIN response (domain does not exist). */
    public static byte[] buildNxDomainResponse(DnsQuery q) {
        byte[] dns = buildDnsHeader(q.transactionId, q.rawPacket, q.rawLength, true);
        dns[3] = (byte) 0x83; // NXDOMAIN rcode = 3
        return wrapInIpUdp(q, dns);
    }

    /** Build a fake A-record response pointing to a SafeSearch IP. */
    public static byte[] buildARecordResponse(DnsQuery q, String ip) {
        try {
            byte[] dnsHeader  = buildDnsHeader(q.transactionId, q.rawPacket, q.rawLength, false);
            byte[] domainName = buildQNameBytes(q.domain);

            // A record answer: NAME(ptr) TYPE CLASS TTL RDLENGTH RDATA
            byte[] answer = new byte[]{
                    (byte)0xC0, 0x0C,           // Name: pointer to QNAME in question section
                    0x00, 0x01,                  // TYPE  A
                    0x00, 0x01,                  // CLASS IN
                    0x00, 0x00, 0x00, 0x3C,     // TTL   60 s
                    0x00, 0x04,                  // RDLENGTH 4
                    parseIpByte(ip, 0),
                    parseIpByte(ip, 1),
                    parseIpByte(ip, 2),
                    parseIpByte(ip, 3)
            };

            // DNS response = header + question section + answer
            int questionLen = domainName.length + 4; // QNAME + QTYPE(2) + QCLASS(2)
            byte[] qSection = buildQuestionSection(q.rawPacket, q.rawLength, questionLen);

            byte[] dns = concat(dnsHeader, qSection, answer);
            // Set ANCOUNT = 1
            dns[6] = 0x00; dns[7] = 0x01;

            return wrapInIpUdp(q, dns);
        } catch (Exception e) {
            return buildNxDomainResponse(q);
        }
    }

    /** Wrap a real upstream DNS response back into an IP/UDP packet for the tunnel. */
    public static byte[] wrapUpstreamResponse(DnsQuery q, byte[] dnsResponse) {
        return wrapInIpUdp(q, dnsResponse);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static byte[] buildDnsHeader(int txId, byte[] orig, int origLen, boolean isNx) {
        byte[] hdr = new byte[12];
        hdr[0] = (byte)(txId >> 8);
        hdr[1] = (byte)(txId & 0xFF);
        // Flags: QR=1(response), Opcode=0, AA=1, TC=0, RD=1, RA=1
        hdr[2] = (byte) 0x81;
        hdr[3] = isNx ? (byte) 0x83 : (byte) 0x80; // NOERROR or NXDOMAIN
        // QDCOUNT = 1
        hdr[4] = 0x00; hdr[5] = 0x01;
        // ANCOUNT = 0 (updated later if needed)
        hdr[6] = 0x00; hdr[7] = 0x00;
        // NSCOUNT, ARCOUNT = 0
        hdr[8] = 0; hdr[9] = 0; hdr[10] = 0; hdr[11] = 0;
        return hdr;
    }

    private static byte[] buildQuestionSection(byte[] orig, int origLen, int estLen) {
        // Copy question section verbatim from original query (offset 40 onwards)
        int qStart = 40;
        int qLen   = Math.min(estLen, origLen - qStart);
        if (qLen <= 0) return new byte[0];
        byte[] q = new byte[qLen];
        System.arraycopy(orig, qStart, q, 0, qLen);
        return q;
    }

    private static byte[] buildQNameBytes(String domain) {
        String[] parts = domain.split("\\.");
        int size = 1; // null terminator
        for (String p : parts) size += 1 + p.length();
        byte[] out = new byte[size];
        int i = 0;
        for (String p : parts) {
            out[i++] = (byte) p.length();
            for (char c : p.toCharArray()) out[i++] = (byte) c;
        }
        out[i] = 0;
        return out;
    }

    private static byte parseIpByte(String ip, int index) {
        String[] parts = ip.split("\\.");
        return (byte) Integer.parseInt(parts[index]);
    }

    /** Wrap a DNS payload in an IP+UDP packet addressed as the reverse of the query. */
    private static byte[] wrapInIpUdp(DnsQuery q, byte[] dnsPayload) {
        int totalLen = 20 + 8 + dnsPayload.length;
        byte[] pkt = new byte[totalLen];

        // IP header
        pkt[0]  = 0x45;                        // Version=4, IHL=5
        pkt[1]  = 0x00;                        // DSCP
        pkt[2]  = (byte)(totalLen >> 8);
        pkt[3]  = (byte)(totalLen & 0xFF);
        pkt[4]  = 0x00; pkt[5] = 0x00;        // Identification
        pkt[6]  = 0x40; pkt[7] = 0x00;        // Flags: Don't Fragment
        pkt[8]  = 0x40;                        // TTL = 64
        pkt[9]  = 0x11;                        // Protocol = UDP
        pkt[10] = 0x00; pkt[11] = 0x00;       // Checksum (filled below)

        // Swap src/dst IPs (response goes back to the original sender)
        System.arraycopy(q.dstIp, 0, pkt, 12, 4); // source = original dest
        System.arraycopy(q.srcIp, 0, pkt, 16, 4); // dest   = original source

        // IP checksum
        int cksum = ipChecksum(pkt, 0, 20);
        pkt[10] = (byte)(cksum >> 8);
        pkt[11] = (byte)(cksum & 0xFF);

        // UDP header
        int udpLen = 8 + dnsPayload.length;
        pkt[20] = (byte)(q.dstPort >> 8); pkt[21] = (byte)(q.dstPort & 0xFF); // src
        pkt[22] = (byte)(q.srcPort >> 8); pkt[23] = (byte)(q.srcPort & 0xFF); // dst
        pkt[24] = (byte)(udpLen >> 8);    pkt[25] = (byte)(udpLen & 0xFF);
        pkt[26] = 0x00; pkt[27] = 0x00;   // Checksum (optional for IPv4)

        // DNS payload
        System.arraycopy(dnsPayload, 0, pkt, 28, dnsPayload.length);

        return pkt;
    }

    private static int ipChecksum(byte[] buf, int offset, int len) {
        int sum = 0;
        for (int i = offset; i < offset + len - 1; i += 2) {
            sum += ((buf[i] & 0xFF) << 8) | (buf[i + 1] & 0xFF);
        }
        if ((len & 1) != 0) sum += (buf[offset + len - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return ~sum & 0xFFFF;
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) { System.arraycopy(a, 0, out, pos, a.length); pos += a.length; }
        return out;
    }
}