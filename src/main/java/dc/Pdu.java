package dc;

import dc.proto.DescobertaResposta;
import dc.proto.Grandeza;
import dc.proto.Leitura;
import dc.proto.TipoFonte;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Locale;
import java.util.Random;

/**
 * PDU (unidade de distribuição de energia): fonte não controlável que envia o consumo
 * do rack em watts a cada INTERVALO_MS. Digite "q" no terminal para simular um pico
 * de consumo e "n" para voltar ao normal (útil na demonstração).
 */
public class Pdu {
    static { System.setProperty("java.net.preferIPv4Stack", "true"); }

    static final double NORMAL_W = 3500;
    static final double PICO_W = 6500;
    static final int INTERVALO_MS = 2000;

    private final Random aleatorio = new Random();
    private volatile double baseW = NORMAL_W;
    private volatile InetSocketAddress gateway;

    public static void main(String[] args) throws Exception {
        String id = args.length > 0 ? args[0] : "pdu-rack-A";
        String rack = args.length > 1 ? args[1] : "A";
        new Pdu().executar(id, rack);
    }

    void executar(String id, String rack) throws Exception {
        NetworkInterface ni = Rede.escolherInterface();
        DescobertaResposta modelo = DescobertaResposta.newBuilder()
                .setFonteId(id)
                .setTipo(TipoFonte.PDU)
                .setIp(Rede.ipv4(ni))
                .setPorta(0)
                .setControlavel(false)
                .setRack(rack)
                .setIntervaloMs(INTERVALO_MS)
                .build();

        Thread descoberta = new Thread(new DescobertaFonte(() -> modelo, ni, sol -> {
            InetSocketAddress novo = new InetSocketAddress(sol.getGatewayIp(), sol.getGatewayPortaUdp());
            if (!novo.equals(gateway)) System.out.println("[" + id + "] gateway em " + novo);
            gateway = novo;
        }), "descoberta");
        descoberta.setDaemon(true);
        descoberta.start();

        Thread teclado = new Thread(this::lerTeclado, "teclado");
        teclado.setDaemon(true);
        teclado.start();

        try (DatagramSocket saida = new DatagramSocket()) {
            long seq = 0;
            while (true) {
                InetSocketAddress destino = gateway;
                if (destino != null) {
                    double watts = baseW + (aleatorio.nextDouble() * 2 - 1) * 150;   // ruído de ±150 W
                    Leitura l = Leitura.newBuilder()
                            .setFonteId(id)
                            .setTipo(TipoFonte.PDU)
                            .setRack(rack)
                            .setGrandeza(Grandeza.POTENCIA_W)
                            .setValor(watts)
                            .setTimestampMs(System.currentTimeMillis())
                            .setEvento(false)
                            .setIntervaloMs(INTERVALO_MS)
                            .setSeq(seq++)
                            .build();
                    byte[] dados = l.toByteArray();
                    saida.send(new DatagramPacket(dados, dados.length, destino));
                    System.out.printf(Locale.ROOT, "[%s] enviei %.0f W%n", id, watts);
                }
                Thread.sleep(INTERVALO_MS);
            }
        }
    }

    private void lerTeclado() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
            String linha;
            while ((linha = in.readLine()) != null) {
                if (linha.trim().equalsIgnoreCase("q")) {
                    baseW = PICO_W;
                    System.out.println("(pico de consumo: base " + PICO_W + " W)");
                } else if (linha.trim().equalsIgnoreCase("n")) {
                    baseW = NORMAL_W;
                    System.out.println("(consumo normal: base " + NORMAL_W + " W)");
                }
            }
        } catch (Exception ignorada) {
            // sem teclado: segue sozinha
        }
    }
}
