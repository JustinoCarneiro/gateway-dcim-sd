package dc;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Escolha da interface de rede usada pelo multicast. */
public final class Rede {
    private Rede() {}

    /**
     * A máquina tem várias interfaces (wifi, bridge, loopback); o multicast sai por uma só.
     * Use -Ddc.interface=wlo1 para forçar uma; sem isso, pega a primeira ativa, com
     * suporte a multicast, que não seja loopback e tenha IPv4.
     */
    public static NetworkInterface escolherInterface() throws Exception {
        String nome = System.getProperty("dc.interface");
        if (nome != null) {
            NetworkInterface ni = NetworkInterface.getByName(nome);
            if (ni == null) throw new IllegalArgumentException("interface inexistente: " + nome);
            return ni;
        }
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (ni.isUp() && !ni.isLoopback() && ni.supportsMulticast() && ipv4(ni) != null) {
                return ni;
            }
        }
        throw new IllegalStateException("nenhuma interface adequada; use -Ddc.interface=<nome>");
    }

    public static String ipv4(NetworkInterface ni) {
        for (InetAddress a : Collections.list(ni.getInetAddresses())) {
            if (a instanceof Inet4Address) return a.getHostAddress();
        }
        return null;
    }
}
