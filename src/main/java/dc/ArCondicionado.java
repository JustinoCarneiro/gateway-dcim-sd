package dc;

import dc.proto.ArCondicionadoGrpc;
import dc.proto.ConfigurarRequest;
import dc.proto.DescobertaResposta;
import dc.proto.EstadoAr;
import dc.proto.Grandeza;
import dc.proto.Leitura;
import dc.proto.TipoFonte;
import dc.proto.Vazio;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Locale;
import java.util.Random;

/**
 * Fonte controlável. Três atividades simultâneas:
 *  - thread "descoberta": responde ao multicast do gateway;
 *  - threads do gRPC (pool criado pelo próprio gRPC): atendem Configurar/ObterEstado;
 *  - thread principal: simula a temperatura do ar e envia leituras por UDP.
 *
 * Quando desligada por comando, a fonte continua enviando leituras (com ligado=false):
 * assim o gateway distingue "desligada por comando" de "caiu" (que é ausência de leituras).
 */
public class ArCondicionado {
    static { System.setProperty("java.net.preferIPv4Stack", "true"); }

    static final int PASSO_MS = 100;

    // ---- estado, compartilhado entre a thread do gRPC e a de leituras: tudo synchronized ----
    private boolean ligado = true;
    private double setpointC = 22.0;
    private int ventiladorPct = 50;
    private int intervaloMs = 2000;
    private double temperaturaC = 24.0;   // temperatura simulada do ar
    private final Random aleatorio = new Random();

    private volatile InetSocketAddress gateway;

    public static void main(String[] args) throws Exception {
        String id = args.length > 0 ? args[0] : "ar-01";
        int porta = args.length > 1 ? Integer.parseInt(args[1]) : Config.AR_GRPC_PORTA_PADRAO;
        new ArCondicionado().executar(id, porta);
    }

    synchronized EstadoAr estado() {
        return EstadoAr.newBuilder().setLigado(ligado).setSetpointC(setpointC)
                .setVentiladorPct(ventiladorPct).setIntervaloMs(intervaloMs).build();
    }

    /** Devolve a mensagem de erro, ou null se todos os campos presentes forem válidos. */
    static String validar(ConfigurarRequest r) {
        if (r.hasSetpointC() && !(r.getSetpointC() >= 10 && r.getSetpointC() <= 35))
            return "setpoint deve estar entre 10 e 35 °C";
        if (r.hasVentiladorPct() && (r.getVentiladorPct() < 0 || r.getVentiladorPct() > 100))
            return "ventilador deve estar entre 0 e 100 %";
        if (r.hasIntervaloMs() && (r.getIntervaloMs() < 500 || r.getIntervaloMs() > 60000))
            return "intervalo deve estar entre 500 e 60000 ms";
        return null;
    }

    /** Aplica só os campos presentes (campo ausente = não alterar). */
    synchronized EstadoAr aplicar(ConfigurarRequest r) {
        if (r.hasLigado()) ligado = r.getLigado();
        if (r.hasSetpointC()) setpointC = r.getSetpointC();
        if (r.hasVentiladorPct()) ventiladorPct = r.getVentiladorPct();
        if (r.hasIntervaloMs()) intervaloMs = r.getIntervaloMs();
        return estado();
    }

    /** Evolui a temperatura: com o ar ligado ela tende ao setpoint; desligado, tende a 32 °C. */
    synchronized double avancar(double dtSeg) {
        double alvo = ligado ? setpointC : 32.0;
        double taxa = 0.02 + 0.10 * ventiladorPct / 100.0;   // ventilador maior, resposta mais rápida
        temperaturaC += (alvo - temperaturaC) * Math.min(1.0, taxa * dtSeg)
                + (aleatorio.nextDouble() - 0.5) * 0.05;
        return temperaturaC;
    }

    synchronized int intervaloAtual() {
        return intervaloMs;
    }

    /** Implementação do serviço definido no .proto; chamada pelas threads do gRPC. */
    class Servico extends ArCondicionadoGrpc.ArCondicionadoImplBase {
        @Override
        public void configurar(ConfigurarRequest req, StreamObserver<EstadoAr> resposta) {
            String erro = validar(req);
            if (erro != null) {
                resposta.onError(Status.INVALID_ARGUMENT.withDescription(erro).asRuntimeException());
                return;
            }
            EstadoAr novo = aplicar(req);
            System.out.printf(Locale.ROOT, "[ar] comando aplicado: ligado=%s setpoint=%.1f ventilador=%d%% intervalo=%dms%n",
                    novo.getLigado(), novo.getSetpointC(), novo.getVentiladorPct(), novo.getIntervaloMs());
            resposta.onNext(novo);
            resposta.onCompleted();
        }

        @Override
        public void obterEstado(Vazio req, StreamObserver<EstadoAr> resposta) {
            resposta.onNext(estado());
            resposta.onCompleted();
        }
    }

    void executar(String id, int porta) throws Exception {
        NetworkInterface ni = Rede.escolherInterface();
        String ip = Rede.ipv4(ni);

        Server grpc = ServerBuilder.forPort(porta).addService(new Servico()).build().start();
        System.out.println("[" + id + "] gRPC ouvindo na porta " + porta);

        Thread descoberta = new Thread(new DescobertaFonte(
                () -> DescobertaResposta.newBuilder()
                        .setFonteId(id)
                        .setTipo(TipoFonte.AR_CONDICIONADO)
                        .setIp(ip)
                        .setPorta(porta)               // porta do serviço gRPC
                        .setControlavel(true)
                        .setIntervaloMs(intervaloAtual())
                        .setEstadoInicial(estado())
                        .build(),
                ni,
                sol -> {
                    InetSocketAddress novo = new InetSocketAddress(sol.getGatewayIp(), sol.getGatewayPortaUdp());
                    if (!novo.equals(gateway)) System.out.println("[" + id + "] gateway em " + novo);
                    gateway = novo;
                }), "descoberta");
        descoberta.setDaemon(true);
        descoberta.start();

        enviarLeituras(id);
        grpc.shutdown();
    }

    private void enviarLeituras(String id) throws Exception {
        try (DatagramSocket saida = new DatagramSocket()) {
            long seq = 0;
            long proximo = 0;
            while (true) {
                double temperatura = avancar(PASSO_MS / 1000.0);
                long agora = System.currentTimeMillis();
                InetSocketAddress destino = gateway;
                if (agora >= proximo && destino != null) {
                    EstadoAr est = estado();
                    Leitura l = Leitura.newBuilder()
                            .setFonteId(id)
                            .setTipo(TipoFonte.AR_CONDICIONADO)
                            .setGrandeza(Grandeza.TEMPERATURA_C)
                            .setValor(temperatura)
                            .setTimestampMs(agora)
                            .setEvento(false)
                            .setIntervaloMs(est.getIntervaloMs())
                            .setSeq(seq++)
                            .setEstadoAr(est)
                            .build();
                    byte[] dados = l.toByteArray();
                    saida.send(new DatagramPacket(dados, dados.length, destino));
                    proximo = agora + est.getIntervaloMs();
                    System.out.printf(Locale.ROOT, "[%s] enviei %.1f °C (ligado=%s, setpoint=%.1f)%n",
                            id, temperatura, est.getLigado(), est.getSetpointC());
                }
                Thread.sleep(PASSO_MS);
            }
        }
    }
}
