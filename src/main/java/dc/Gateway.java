package dc;

import com.google.protobuf.InvalidProtocolBufferException;
import dc.proto.ArCondicionadoGrpc;
import dc.proto.ComandoRequest;
import dc.proto.ComandoResponse;
import dc.proto.DescobertaResposta;
import dc.proto.DescobertaSolicitacao;
import dc.proto.EstadoAr;
import dc.proto.Grandeza;
import dc.proto.Leitura;
import dc.proto.ListarFontesResponse;
import dc.proto.PedidoCliente;
import dc.proto.RespostaCliente;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Processo central. Papéis simultâneos, cada um numa thread:
 *  - "udp-leituras": servidor UDP das leituras das fontes;
 *  - agenda de descoberta: cliente multicast, repetido periodicamente;
 *  - "tcp-clientes": servidor TCP do cliente CLI (uma thread por conexão);
 *  - ao comandar uma fonte, atua como cliente gRPC.
 */
public class Gateway {
    static { System.setProperty("java.net.preferIPv4Stack", "true"); }

    private static final int PRAZO_GRPC_S = 3;

    private final Cadastro cadastro = new Cadastro();

    public static void main(String[] args) throws Exception {
        Gateway gw = new Gateway();

        // sobe antes da descoberta, para não perder leituras de fontes que respondem rápido
        new Thread(gw::receberLeituras, "udp-leituras").start();
        new Thread(gw::servirClientes, "tcp-clientes").start();

        // repete o pedido de descoberta, para achar fontes que ligaram depois
        // (ou que perderam o pedido: é UDP)
        ScheduledExecutorService agenda = Executors.newSingleThreadScheduledExecutor();
        agenda.scheduleWithFixedDelay(() -> {
            try {
                gw.descobrir();
            } catch (Exception e) {
                System.out.println("falha na descoberta: " + e);
            }
        }, 0, Config.INTERVALO_DESCOBERTA_S, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ descoberta (multicast)

    /** Envia a solicitação ao grupo multicast e coleta respostas unicast até o fim da janela. */
    void descobrir() throws Exception {
        NetworkInterface ni = Rede.escolherInterface();
        int nonce = new Random().nextInt(Integer.MAX_VALUE);

        DescobertaSolicitacao sol = DescobertaSolicitacao.newBuilder()
                .setGatewayIp(Rede.ipv4(ni))
                .setGatewayPortaUdp(Config.GATEWAY_UDP_PORTA)
                .setNonce(nonce)
                .build();
        byte[] dados = sol.toByteArray();

        // porta efêmera: as respostas voltam para o endereço de origem desta solicitação.
        // Fica separada da porta de leituras porque o datagrama Protobuf não traz tipo:
        // misturar Leitura e DescobertaResposta no mesmo socket impediria distinguir uma da outra.
        try (MulticastSocket sock = new MulticastSocket()) {
            sock.setNetworkInterface(ni);
            sock.send(new DatagramPacket(dados, dados.length,
                    InetAddress.getByName(Config.MULTICAST_GRUPO), Config.MULTICAST_PORTA));

            long fim = System.currentTimeMillis() + Config.JANELA_DESCOBERTA_MS;
            byte[] buf = new byte[2048];
            while (true) {
                long restante = fim - System.currentTimeMillis();
                if (restante <= 0) break;
                sock.setSoTimeout((int) restante);
                DatagramPacket pacote = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(pacote);
                } catch (SocketTimeoutException e) {
                    break;
                }
                try {
                    DescobertaResposta r = DescobertaResposta.parseFrom(
                            ByteBuffer.wrap(pacote.getData(), pacote.getOffset(), pacote.getLength()));
                    if (r.getNonce() != nonce) continue;   // resposta de outra rodada
                    if (cadastro.registrarFonte(r)) {
                        System.out.println("[descoberta] nova fonte: " + r.getFonteId()
                                + " (" + r.getTipo() + ", rack " + r.getRack() + ")");
                    }
                } catch (Exception e) {
                    System.out.println("[descoberta] datagrama inválido descartado");
                }
            }
        }
        System.out.println("[descoberta] rodada concluída: " + cadastro.fontes().size()
                + " fonte(s) cadastrada(s)");
    }

    // ------------------------------------------------------------------ leituras (UDP)

    /** Laço da thread de leituras: um datagrama UDP = uma Leitura. */
    void receberLeituras() {
        try (DatagramSocket sock = new DatagramSocket(Config.GATEWAY_UDP_PORTA)) {
            System.out.println("[udp] ouvindo leituras na porta " + Config.GATEWAY_UDP_PORTA);
            byte[] buf = new byte[2048];
            while (true) {
                DatagramPacket pacote = new DatagramPacket(buf, buf.length);
                sock.receive(pacote);
                try {
                    Leitura l = Leitura.parseFrom(
                            ByteBuffer.wrap(pacote.getData(), pacote.getOffset(), pacote.getLength()));
                    // Protobuf aceita quase qualquer sequência de bytes: validamos o conteúdo
                    if (l.getFonteId().isEmpty() || l.getGrandeza() == Grandeza.GRANDEZA_DESCONHECIDA) {
                        System.out.println("[udp] leitura sem fonte/grandeza descartada");
                        continue;
                    }
                    cadastro.registrarLeitura(l, System.currentTimeMillis());
                    System.out.printf(Locale.ROOT, "[udp] %s %s = %.1f%s%n", l.getFonteId(), l.getGrandeza(),
                            l.getValor(), l.getEvento() ? "   <<< EVENTO" : "");
                } catch (Exception e) {
                    System.out.println("[udp] datagrama inválido descartado");
                }
            }
        } catch (Exception e) {
            System.out.println("[udp] encerrou: " + e);
        }
    }

    // ------------------------------------------------------------------ clientes (TCP)

    void servirClientes() {
        try (ServerSocket servidor = new ServerSocket(Config.GATEWAY_TCP_PORTA)) {
            System.out.println("[tcp] aguardando clientes na porta " + Config.GATEWAY_TCP_PORTA);
            while (true) {
                Socket cliente = servidor.accept();
                new Thread(() -> atender(cliente), "cliente-" + cliente.getPort()).start();
            }
        } catch (IOException e) {
            System.out.println("[tcp] encerrou: " + e);
        }
    }

    /** Uma conexão: lê pedidos enquadrados e responde um a um até o cliente desconectar. */
    void atender(Socket socket) {
        String quem = socket.getRemoteSocketAddress().toString();
        System.out.println("[tcp] cliente conectado: " + quem);
        try (socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {
            while (true) {
                PedidoCliente pedido;
                try {
                    pedido = Quadro.receber(in, PedidoCliente.parser());
                } catch (InvalidProtocolBufferException e) {
                    // quadro completo, mas conteúdo inválido: o fluxo segue alinhado
                    Quadro.enviar(out, erro("mensagem inválida"));
                    continue;
                }
                Quadro.enviar(out, processar(pedido));
            }
        } catch (EOFException e) {
            System.out.println("[tcp] cliente desconectou: " + quem);
        } catch (IOException e) {
            // inclui quadro fora do limite: o fluxo perdeu o alinhamento, só resta fechar
            System.out.println("[tcp] conexão encerrada (" + quem + "): " + e.getMessage());
        }
    }

    RespostaCliente processar(PedidoCliente pedido) {
        return switch (pedido.getPedidoCase()) {
            case LISTAR -> RespostaCliente.newBuilder().setOk(true)
                    .setListar(ListarFontesResponse.newBuilder().addAllFontes(cadastro.listar())).build();
            case COMANDO -> comandar(pedido.getComando());
            case CONSULTA -> {
                try {
                    yield RespostaCliente.newBuilder().setOk(true).setConsulta(
                            cadastro.consultar(pedido.getConsulta().getTipo(), pedido.getConsulta().getJanelaS())).build();
                } catch (IllegalArgumentException e) {
                    yield erro(e.getMessage());
                }
            }
            case PEDIDO_NOT_SET -> erro("pedido vazio");
        };
    }

    // ------------------------------------------------------------------ comandos (gRPC)

    /** O gateway é cliente gRPC da fonte controlável. */
    RespostaCliente comandar(ComandoRequest cmd) {
        String id = cmd.getFonteId();
        Optional<DescobertaResposta> fonte = cadastro.fonte(id);
        if (fonte.isEmpty()) return erro("fonte inexistente: " + id);
        if (!fonte.get().getControlavel()) return erro("fonte " + id + " não é controlável");

        // um canal por comando: simples. Com muitos comandos valeria guardar o canal por fonte
        ManagedChannel canal = ManagedChannelBuilder
                .forAddress(fonte.get().getIp(), fonte.get().getPorta()).usePlaintext().build();
        try {
            EstadoAr novo = ArCondicionadoGrpc.newBlockingStub(canal)
                    .withDeadlineAfter(PRAZO_GRPC_S, TimeUnit.SECONDS)   // não espera para sempre
                    .configurar(cmd.getConfig());
            cadastro.atualizarEstadoAr(id, novo);
            System.out.println("[grpc] comando aplicado em " + id);
            return RespostaCliente.newBuilder().setOk(true)
                    .setComando(ComandoResponse.newBuilder().setFonte(cadastro.info(id))).build();
        } catch (StatusRuntimeException e) {
            Status.Code codigo = e.getStatus().getCode();
            System.out.println("[grpc] falha ao comandar " + id + ": " + codigo);
            if (codigo == Status.Code.INVALID_ARGUMENT) {
                return erro("fonte recusou o comando: " + e.getStatus().getDescription());
            }
            return erro("fonte " + id + " não respondeu (" + codigo + ")");
        } finally {
            canal.shutdownNow();
        }
    }

    static RespostaCliente erro(String mensagem) {
        return RespostaCliente.newBuilder().setOk(false).setErro(mensagem).build();
    }
}
