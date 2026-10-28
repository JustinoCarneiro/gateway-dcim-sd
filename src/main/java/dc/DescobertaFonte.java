package dc;

import dc.proto.DescobertaResposta;
import dc.proto.DescobertaSolicitacao;

import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Lado da fonte na descoberta: entra no grupo multicast, espera a solicitação do gateway
 * e responde por unicast ao endereço de origem do datagrama.
 */
public class DescobertaFonte implements Runnable {
    private final Supplier<DescobertaResposta> modelo;
    private final NetworkInterface interfaceRede;
    private final Consumer<DescobertaSolicitacao> aoReceber;

    /**
     * @param modelo    fornece a resposta (sem nonce; o nonce é copiado de cada solicitação).
     *                  É um Supplier porque o estado de uma fonte controlável muda com o tempo
     * @param aoReceber chamado a cada solicitação válida; a fonte usa para aprender
     *                  o destino das leituras (gateway_ip e gateway_porta_udp)
     */
    public DescobertaFonte(Supplier<DescobertaResposta> modelo, NetworkInterface interfaceRede,
                           Consumer<DescobertaSolicitacao> aoReceber) {
        this.modelo = modelo;
        this.interfaceRede = interfaceRede;
        this.aoReceber = aoReceber;
    }

    @Override
    public void run() {
        try (MulticastSocket sock = new MulticastSocket(null)) {
            // várias fontes na mesma máquina escutam a mesma porta do grupo
            sock.setReuseAddress(true);
            sock.bind(new InetSocketAddress(Config.MULTICAST_PORTA));
            sock.joinGroup(new InetSocketAddress(Config.MULTICAST_GRUPO, 0), interfaceRede);
            System.out.println("[" + modelo.get().getFonteId() + "] no grupo "
                    + Config.MULTICAST_GRUPO + ":" + Config.MULTICAST_PORTA);

            byte[] buf = new byte[2048];
            while (!Thread.currentThread().isInterrupted()) {
                DatagramPacket pacote = new DatagramPacket(buf, buf.length);
                sock.receive(pacote);
                DescobertaSolicitacao sol;
                try {
                    sol = DescobertaSolicitacao.parseFrom(
                            ByteBuffer.wrap(pacote.getData(), pacote.getOffset(), pacote.getLength()));
                } catch (Exception e) {
                    continue;   // datagrama que não é uma solicitação válida: ignora
                }
                if (sol.getGatewayPortaUdp() == 0 || sol.getGatewayIp().isEmpty()) continue;

                aoReceber.accept(sol);
                byte[] resposta = modelo.get().toBuilder().setNonce(sol.getNonce()).build().toByteArray();
                sock.send(new DatagramPacket(resposta, resposta.length, pacote.getSocketAddress()));
            }
        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) e.printStackTrace();
        }
    }
}
