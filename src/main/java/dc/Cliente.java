package dc;

import dc.proto.ComandoRequest;
import dc.proto.ConfigurarRequest;
import dc.proto.ConsultaRequest;
import dc.proto.FonteInfo;
import dc.proto.HistoricoRequest;
import dc.proto.ListarFontesRequest;
import dc.proto.PedidoCliente;
import dc.proto.RespostaCliente;
import dc.proto.TipoConsulta;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Cliente de linha de comando: fala com o gateway por TCP, mensagens Protobuf enquadradas.
 * Uso: Cliente [host] [porta]
 */
public class Cliente {
    private static final DateTimeFormatter HORA =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATA_HORA =
            DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final String AJUDA = """
            comandos:
              listar                          fontes e estados
              ligar <id> | desligar <id>      liga/desliga o ar-condicionado
              setpoint <id> <graus>           ex.: setpoint ar-01 20
              ventilador <id> <0-100>
              intervalo <id> <ms>             período de envio das leituras
              consulta media|rack|pico [janela_s]
              historico [id] [n]              últimas n leituras gravadas no CSV (padrão 20)
              ajuda | sair""";

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int porta = args.length > 1 ? Integer.parseInt(args[1]) : Config.GATEWAY_TCP_PORTA;

        try (Socket socket = new Socket(host, porta)) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            System.out.println("conectado ao gateway " + host + ":" + porta + "\n" + AJUDA);

            BufferedReader teclado = new BufferedReader(new InputStreamReader(System.in));
            while (true) {
                System.out.print("dc> ");
                System.out.flush();
                String linha = teclado.readLine();
                if (linha == null || linha.trim().equals("sair")) break;
                linha = linha.trim();
                if (linha.isEmpty()) continue;
                if (linha.equals("ajuda")) {
                    System.out.println(AJUDA);
                    continue;
                }

                PedidoCliente pedido;
                try {
                    pedido = montarPedido(linha.split("\\s+"));
                } catch (IllegalArgumentException e) {
                    System.out.println("erro: " + e.getMessage());
                    continue;
                }
                Quadro.enviar(out, pedido);
                mostrar(Quadro.receber(in, RespostaCliente.parser()));
            }
        } catch (java.net.ConnectException e) {
            System.out.println("não consegui conectar em " + host + ":" + porta + " (o gateway está no ar?)");
        } catch (java.io.IOException e) {
            System.out.println("conexão com o gateway encerrada (" + e.getClass().getSimpleName() + ")");
        }
    }

    static PedidoCliente montarPedido(String[] p) {
        switch (p[0]) {
            case "listar":
                return PedidoCliente.newBuilder().setListar(ListarFontesRequest.getDefaultInstance()).build();
            case "ligar":
                exigir(p, 2);
                return comando(p, ConfigurarRequest.newBuilder().setLigado(true));
            case "desligar":
                exigir(p, 2);
                return comando(p, ConfigurarRequest.newBuilder().setLigado(false));
            case "setpoint":
                exigir(p, 3);   // antes de ler p[2]: sem o argumento, p[2] não existe
                return comando(p, ConfigurarRequest.newBuilder().setSetpointC(numero(p[2])));
            case "ventilador":
                exigir(p, 3);
                return comando(p, ConfigurarRequest.newBuilder().setVentiladorPct((int) numero(p[2])));
            case "intervalo":
                exigir(p, 3);
                return comando(p, ConfigurarRequest.newBuilder().setIntervaloMs((int) numero(p[2])));
            case "consulta": {
                if (p.length < 2) throw new IllegalArgumentException("use: consulta media|rack|pico [janela_s]");
                TipoConsulta tipo = switch (p[1]) {
                    case "media" -> TipoConsulta.TEMPERATURA_MEDIA;
                    case "rack" -> TipoConsulta.RACK_MAIS_QUENTE;
                    case "pico" -> TipoConsulta.PICO_ENERGIA;
                    default -> throw new IllegalArgumentException("consulta desconhecida: " + p[1]);
                };
                int janela = p.length > 2 ? (int) numero(p[2]) : 0;   // 0 = padrão do gateway
                return PedidoCliente.newBuilder()
                        .setConsulta(ConsultaRequest.newBuilder().setTipo(tipo).setJanelaS(janela)).build();
            }
            case "historico": {
                // historico [id] [n]  — um único argumento numérico é o n
                String id = "";
                int n = 0;   // 0 = padrão do gateway
                if (p.length > 3) throw new IllegalArgumentException("use: historico [id] [n]");
                if (p.length == 2 && p[1].matches("\\d+")) {
                    n = (int) numero(p[1]);
                } else {
                    if (p.length > 1) id = p[1];
                    if (p.length > 2) n = (int) numero(p[2]);
                }
                if (n < 0) throw new IllegalArgumentException("n deve ser positivo");
                return PedidoCliente.newBuilder()
                        .setHistorico(HistoricoRequest.newBuilder().setFonteId(id).setLimite(n)).build();
            }
            default:
                throw new IllegalArgumentException("comando desconhecido (digite ajuda)");
        }
    }

    private static void exigir(String[] p, int minimo) {
        if (p.length < minimo) throw new IllegalArgumentException("faltam argumentos (digite ajuda)");
    }

    private static PedidoCliente comando(String[] p, ConfigurarRequest.Builder cfg) {
        return PedidoCliente.newBuilder()
                .setComando(ComandoRequest.newBuilder().setFonteId(p[1]).setConfig(cfg)).build();
    }

    private static double numero(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("número inválido: " + s);
        }
    }

    static void mostrar(RespostaCliente r) {
        if (!r.getOk()) {
            System.out.println("ERRO: " + r.getErro());
            return;
        }
        switch (r.getRespostaCase()) {
            case LISTAR -> {
                System.out.printf("%-14s %-19s %-22s %-5s %-8s %-9s %s%n",
                        "ID", "TIPO", "ENDEREÇO", "RACK", "ESTADO", "ÚLT.LEIT.", "DETALHE");
                r.getListar().getFontesList().forEach(Cliente::linha);
            }
            case COMANDO -> {
                System.out.println("comando aplicado:");
                linha(r.getComando().getFonte());
            }
            case CONSULTA -> {
                var c = r.getConsulta();
                if (!c.getTemDados()) {
                    System.out.println(c.getTipo() + ": sem dados nos últimos " + c.getJanelaS() + " s");
                } else {
                    System.out.printf(Locale.ROOT, "%s (janela %d s, %d amostras): %.2f%s%n", c.getTipo(),
                            c.getJanelaS(), c.getAmostras(), c.getValor(),
                            c.getReferencia().isEmpty() ? "" : "  [" + c.getReferencia() + "]");
                }
            }
            case HISTORICO -> {
                var h = r.getHistorico();
                if (h.getLeiturasCount() == 0) {
                    System.out.println("histórico vazio");
                    break;
                }
                System.out.printf("%-20s %-14s %-5s %-14s %s%n", "RECEBIDA EM", "FONTE", "RACK", "GRANDEZA", "VALOR");
                h.getLeiturasList().forEach(g -> System.out.printf(Locale.ROOT, "%-20s %-14s %-5s %-14s %.2f%s%n",
                        DATA_HORA.format(Instant.ofEpochMilli(g.getRecebidoEmMs())), g.getLeitura().getFonteId(),
                        g.getLeitura().getRack().isEmpty() ? "-" : g.getLeitura().getRack(),
                        g.getLeitura().getGrandeza(), g.getLeitura().getValor(),
                        g.getLeitura().getEvento() ? "   <<< EVENTO" : ""));
            }
            default -> System.out.println("(resposta vazia)");
        }
    }

    private static void linha(FonteInfo f) {
        String ultima = f.getUltimaLeituraMs() == 0 ? "-" : HORA.format(Instant.ofEpochMilli(f.getUltimaLeituraMs()));
        String detalhe = f.hasEstadoAr()
                ? String.format(Locale.ROOT, "%s setpoint=%.1f vent=%d%% intervalo=%dms",
                        f.getEstadoAr().getLigado() ? "LIGADO" : "DESLIGADO", f.getEstadoAr().getSetpointC(),
                        f.getEstadoAr().getVentiladorPct(), f.getEstadoAr().getIntervaloMs())
                : "";
        System.out.printf("%-14s %-19s %-22s %-5s %-8s %-9s %s%n", f.getFonteId(), f.getTipo(),
                f.getIp() + ":" + f.getPorta(), f.getRack().isEmpty() ? "-" : f.getRack(),
                f.getEstado(), ultima, detalhe);
    }
}
