package com.ticketing.security;

import com.ticketing.config.ClientAddressProperties;
import com.ticketing.config.ClientAddressProperties.Mode;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** 只接受数值IP，无DNS解析；从最近代理向外剥离受信节点，遇到不可信节点即停止。 */
public class ClientAddressResolver {
    private final ClientAddressProperties properties;
    private final List<Network> networks;

    public ClientAddressResolver(ClientAddressProperties properties) {
        this.properties = properties;
        if ((properties.mode() == Mode.DIRECT) != properties.trustedProxies().isEmpty()) {
            throw new IllegalArgumentException("直连模式不得配置代理，受信代理模式必须显式配置代理范围");
        }
        networks = properties.trustedProxies().stream().map(Network::parse).toList();
    }

    public String resolve(HttpServletRequest request) {
        InetAddress address = literal(request.getRemoteAddr());
        if (properties.mode() == Mode.DIRECT || !trusted(address)) return address.getHostAddress();
        var headers = request.getHeaders("X-Forwarded-For");
        if (headers == null || !headers.hasMoreElements()) throw invalid();
        String header = headers.nextElement();
        if (headers.hasMoreElements() || header == null || header.isBlank()
                || header.length() > properties.maxForwardedHeaderLength()
                || header.chars().anyMatch(character -> character < 32 || character > 126)) throw invalid();
        String[] values = header.split(",", -1);
        if (values.length > properties.maxForwardedHops()) throw invalid();
        List<InetAddress> chain = new ArrayList<>();
        for (String value : values) chain.add(literal(value.strip()));
        for (int index = chain.size() - 1; index >= 0 && trusted(address); index--) address = chain.get(index);
        return address.getHostAddress();
    }

    private boolean trusted(InetAddress address) { return networks.stream().anyMatch(network -> network.contains(address)); }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("安全来源信息无效"); }

    private static InetAddress literal(String value) {
        if (value == null || value.isEmpty()) throw invalid();
        try {
            if (!value.contains(":")) {
                String[] parts = value.split("\\.", -1);
                if (parts.length != 4) throw invalid();
                byte[] bytes = new byte[4];
                for (int index = 0; index < parts.length; index++) {
                    String part = parts[index];
                    if (!part.matches("0|[1-9][0-9]{0,2}")) throw invalid();
                    int number = Integer.parseInt(part);
                    if (number > 255) throw invalid();
                    bytes[index] = (byte) number;
                }
                return InetAddress.getByAddress(bytes);
            }
            if (!value.matches("[0-9a-fA-F:.]+")) throw invalid();
            if (value.contains(".")) literal(value.substring(value.lastIndexOf(':') + 1));
            // 含冒号且只含IPv6数值字符，InetAddress只做字面量解析，不会查询主机名。
            return InetAddress.getByName(value);
        } catch (UnknownHostException exception) { throw invalid(); }
    }

    private record Network(byte[] address, int prefix) {
        static Network parse(String value) {
            String[] parts = value.split("/", -1);
            if (parts.length > 2) throw invalid();
            byte[] bytes = literal(parts[0]).getAddress();
            int prefix = bytes.length * Byte.SIZE;
            if (parts.length == 2) {
                if (!parts[1].matches("[1-9][0-9]{0,2}")) throw invalid();
                prefix = Integer.parseInt(parts[1]);
            }
            if (prefix > bytes.length * Byte.SIZE) throw invalid();
            return new Network(bytes, prefix);
        }
        boolean contains(InetAddress candidate) {
            byte[] bytes = candidate.getAddress();
            if (bytes.length != address.length) return false;
            for (int index = 0; index < prefix; index++) {
                int mask = 1 << (7 - index % Byte.SIZE);
                if ((bytes[index / Byte.SIZE] & mask) != (address[index / Byte.SIZE] & mask)) return false;
            }
            return true;
        }
    }
}
