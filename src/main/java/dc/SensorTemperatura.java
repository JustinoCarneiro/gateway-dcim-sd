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
 * Fonte não controlável: envia a temperatura do rack a cada INTERVALO_MS e, além disso,
 * envia imediatamente quando a temperatura cruza o limiar (subindo ou descendo).
 *
 * Para a demonstração, digite "q" no terminal do sensor para simular rack esquentando
 * e "n" para voltar ao normal.
 */
public class SensorTemperatura {
    static { System.setProperty("java.net.preferIPv4Stack", "true"); }

    static final double LIMIAR_C = 27.0;    // topo da faixa recomendada da ASHRAE
    static final double NORMAL_C = 22.0;
    static final double QUENTE_C = 29.0;
    static final int INTERVALO_MS = 2000;
    static final int AMOSTRAGEM_MS = 200;

    private final Random aleatorio = new Random();
    private volatile double base = NORMAL_C;
    private volatile InetSocketAddress gateway;   // aprendido na solicitação de descoberta

    public static void main(String[] args) throws Exception {
        String id = args.length > 0 ? args[0] : "temp-rack-A";
        String rack = args.length > 1 ? args[1] : "A";
        new SensorTemperatura().executar(id, rack);
    }

    void executar(String id, String rack) throws Exception {
        NetworkInterface ni = Rede.escolherInterface();
        DescobertaResposta modelo = DescobertaResposta.newBuilder()
                .setFonteId(id)
                .setTipo(TipoFonte.SENSOR_TEMPERATURA)
                .setIp(Rede.ipv4(ni))
                .setPorta(0)               // não controlável: sem gRPC
                .setControlavel(false)
                .setRack(rack)
                .setIntervaloMs(INTERVALO_MS)
                .build();

        // thread 1: descoberta (fica bloqueada esperando pedidos multicast)
        Thread descoberta = new Thread(new DescobertaFonte(() -> modelo, ni, sol -> {
            InetSocketAddress novo = new InetSocketAddress(sol.getGatewayIp(), sol.getGatewayPortaUdp());
            if (!novo.equals(gateway)) System.out.println("[" + id + "] gateway em " + novo);
            gateway = novo;
        }), "descoberta");
        descoberta.setDaemon(true);
        descoberta.start();

        // thread 2: teclado, só para a demonstração
        Thread teclado = new Thread(this::lerTeclado, "teclado");
        teclado.setDaemon(true);
        teclado.start();

        // thread principal: amostragem e envio
        enviarLeituras(id, rack);
    }

    private void lerTeclado() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
            String linha;
            while ((linha = in.readLine()) != null) {
                if (linha.trim().equalsIgnoreCase("q")) {
                    base = QUENTE_C;
                    System.out.println("(rack esquentando: base " + QUENTE_C + " °C)");
                } else if (linha.trim().equalsIgnoreCase("n")) {
                    base = NORMAL_C;
                    System.out.println("(rack normal: base " + NORMAL_C + " °C)");
                }
            }
        } catch (Exception ignorada) {
            // sem teclado (stdin fechado): o sensor segue sozinho
        }
    }

    private void enviarLeituras(String id, String rack) throws Exception {
        try (DatagramSocket saida = new DatagramSocket()) {
            boolean acima = false;
            long seq = 0;
            long proximoPeriodico = 0;

            while (true) {
                double valor = base + (aleatorio.nextDouble() * 2 - 1);   // ruído de ±1 °C
                boolean agoraAcima = valor > LIMIAR_C;
                long agora = System.currentTimeMillis();

                boolean evento = agoraAcima != acima;   // cruzou o limiar
                if (evento || agora >= proximoPeriodico) {
                    acima = agoraAcima;
                    proximoPeriodico = agora + INTERVALO_MS;
                    InetSocketAddress destino = gateway;
                    if (destino != null) {
                        Leitura l = Leitura.newBuilder()
                                .setFonteId(id)
                                .setTipo(TipoFonte.SENSOR_TEMPERATURA)
                                .setRack(rack)
                                .setGrandeza(Grandeza.TEMPERATURA_C)
                                .setValor(valor)
                                .setTimestampMs(agora)
                                .setEvento(evento)
                                .setIntervaloMs(INTERVALO_MS)
                                .setSeq(seq++)
                                .build();
                        byte[] dados = l.toByteArray();
                        saida.send(new DatagramPacket(dados, dados.length, destino));
                        System.out.printf(Locale.ROOT, "[%s] enviei %.1f °C%s%n", id, valor,
                                evento ? (agoraAcima ? "  EVENTO: passou de " + LIMIAR_C
                                                      : "  EVENTO: voltou abaixo de " + LIMIAR_C) : "");
                    }
                }
                Thread.sleep(AMOSTRAGEM_MS);
            }
        }
    }
}
