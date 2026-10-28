package dc;

/** Endereços, portas e parâmetros compartilhados por todos os processos. */
public final class Config {
    public static final String MULTICAST_GRUPO = "239.255.42.99";
    public static final int MULTICAST_PORTA = 5007;

    public static final int GATEWAY_UDP_PORTA = 5008;   // leituras das fontes
    public static final int GATEWAY_TCP_PORTA = 5009;   // cliente CLI

    public static final int AR_GRPC_PORTA_PADRAO = 50051;

    /** Fonte vira INATIVA após esse número de intervalos anunciados sem nenhuma leitura. */
    public static final int TOLERANCIA_INATIVIDADE = 3;
    public static final int TAMANHO_MAX_QUADRO = 1 << 20;   // 1 MiB

    public static final int JANELA_PADRAO_S = 300;            // consultas sem janela explícita
    public static final long RETENCAO_MS = 60 * 60 * 1000L;   // histórico mais antigo que 1 h é descartado

    public static final int JANELA_DESCOBERTA_MS = 3000;
    /** Intervalo entre pedidos de descoberta; -Ddc.descoberta.s=8 encurta para testes. */
    public static final long INTERVALO_DESCOBERTA_S = Long.getLong("dc.descoberta.s", 30);

    private Config() {}
}
