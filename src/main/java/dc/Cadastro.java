package dc;

import dc.proto.DescobertaResposta;
import dc.proto.EstadoAr;
import dc.proto.EstadoFonte;
import dc.proto.FonteInfo;
import dc.proto.Leitura;

import dc.proto.ConsultaResponse;
import dc.proto.Grandeza;
import dc.proto.TipoConsulta;
import dc.proto.TipoFonte;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Estado do gateway em memória: fontes descobertas e histórico de leituras.
 * Várias threads mexem aqui ao mesmo tempo (descoberta, recepção UDP, clientes TCP),
 * por isso todo acesso é synchronized.
 */
public class Cadastro {
    /** Leitura + instante de chegada no gateway (usamos o relógio do gateway, não o da fonte). */
    public record Registro(Leitura leitura, long recebidoEm) {}

    private final Map<String, DescobertaResposta> fontes = new LinkedHashMap<>();
    private final Map<String, Long> descobertaEm = new HashMap<>();
    private final Map<String, Long> ultimaLeitura = new HashMap<>();
    private final Map<String, Integer> intervaloMs = new HashMap<>();
    private final Map<String, EstadoAr> estadoAr = new HashMap<>();
    private final Deque<Registro> historico = new ArrayDeque<>();   // em ordem de chegada

    /** @return true se a fonte ainda não era conhecida */
    public synchronized boolean registrarFonte(DescobertaResposta r) {
        String id = r.getFonteId();
        boolean nova = !fontes.containsKey(id);
        fontes.put(id, r);
        if (nova) descobertaEm.put(id, System.currentTimeMillis());
        if (r.hasEstadoInicial()) estadoAr.put(id, r.getEstadoInicial());
        return nova;
    }

    public synchronized void registrarLeitura(Leitura l, long recebidoEm) {
        historico.addLast(new Registro(l, recebidoEm));
        // a memória não pode crescer sem limite: descarta o que passou da retenção
        while (!historico.isEmpty() && historico.peekFirst().recebidoEm() < recebidoEm - Config.RETENCAO_MS) {
            historico.pollFirst();
        }
        ultimaLeitura.put(l.getFonteId(), recebidoEm);
        if (l.getIntervaloMs() > 0) intervaloMs.put(l.getFonteId(), l.getIntervaloMs());
        if (l.hasEstadoAr()) estadoAr.put(l.getFonteId(), l.getEstadoAr());
    }

    /** Guarda o estado devolvido por um comando gRPC bem-sucedido. */
    public synchronized void atualizarEstadoAr(String fonteId, EstadoAr estado) {
        estadoAr.put(fonteId, estado);
    }

    public synchronized Optional<DescobertaResposta> fonte(String fonteId) {
        return Optional.ofNullable(fontes.get(fonteId));
    }

    public synchronized List<DescobertaResposta> fontes() {
        return new ArrayList<>(fontes.values());
    }

    public synchronized int totalLeituras() {
        return historico.size();
    }

    /**
     * Consultas agregadas sobre as leituras que CHEGARAM nos últimos janelaS segundos
     * (relógio do gateway). Sem leituras na janela: tem_dados = false.
     *  - TEMPERATURA_MEDIA: média de todas as leituras dos sensores de temperatura (°C);
     *  - RACK_MAIS_QUENTE: rack com maior média de temperatura; valor = essa média;
     *  - PICO_ENERGIA: maior leitura de potência das PDUs (W); referência = a PDU que a gerou.
     * As leituras do ar-condicionado não entram: medem o ar de saída, não um rack.
     */
    public synchronized ConsultaResponse consultar(TipoConsulta tipo, int janelaS) {
        int janela = janelaS > 0 ? janelaS : Config.JANELA_PADRAO_S;
        long desde = System.currentTimeMillis() - janela * 1000L;

        ConsultaResponse.Builder r = ConsultaResponse.newBuilder().setTipo(tipo).setJanelaS(janela);
        switch (tipo) {
            case TEMPERATURA_MEDIA -> {
                double soma = 0;
                int n = 0;
                for (Registro g : historico) {
                    if (g.recebidoEm() >= desde && ehTemperaturaDeRack(g.leitura())) {
                        soma += g.leitura().getValor();
                        n++;
                    }
                }
                if (n > 0) r.setTemDados(true).setValor(soma / n).setAmostras(n);
            }
            case RACK_MAIS_QUENTE -> {
                Map<String, double[]> porRack = new HashMap<>();   // rack -> {soma, n}
                for (Registro g : historico) {
                    if (g.recebidoEm() >= desde && ehTemperaturaDeRack(g.leitura())
                            && !g.leitura().getRack().isEmpty()) {
                        double[] acc = porRack.computeIfAbsent(g.leitura().getRack(), k -> new double[2]);
                        acc[0] += g.leitura().getValor();
                        acc[1]++;
                    }
                }
                String melhor = null;
                double maiorMedia = Double.NEGATIVE_INFINITY;
                int amostras = 0;
                for (Map.Entry<String, double[]> e : porRack.entrySet()) {
                    double media = e.getValue()[0] / e.getValue()[1];
                    if (media > maiorMedia) {
                        maiorMedia = media;
                        melhor = e.getKey();
                    }
                    amostras += (int) e.getValue()[1];
                }
                if (melhor != null) {
                    r.setTemDados(true).setValor(maiorMedia).setReferencia("rack " + melhor).setAmostras(amostras);
                }
            }
            case PICO_ENERGIA -> {
                double maior = Double.NEGATIVE_INFINITY;
                String fonte = null;
                int n = 0;
                for (Registro g : historico) {
                    Leitura l = g.leitura();
                    if (g.recebidoEm() >= desde && l.getTipo() == TipoFonte.PDU
                            && l.getGrandeza() == Grandeza.POTENCIA_W) {
                        n++;
                        if (l.getValor() > maior) {
                            maior = l.getValor();
                            fonte = l.getFonteId();
                        }
                    }
                }
                if (fonte != null) r.setTemDados(true).setValor(maior).setReferencia(fonte).setAmostras(n);
            }
            default -> throw new IllegalArgumentException("consulta desconhecida: " + tipo);
        }
        return r.build();
    }

    private static boolean ehTemperaturaDeRack(Leitura l) {
        return l.getTipo() == TipoFonte.SENSOR_TEMPERATURA && l.getGrandeza() == Grandeza.TEMPERATURA_C;
    }

    /** Visão das fontes para o cliente, com o estado ATIVA/INATIVA calculado agora. */
    public synchronized List<FonteInfo> listar() {
        long agora = System.currentTimeMillis();
        List<FonteInfo> lista = new ArrayList<>();
        for (DescobertaResposta d : fontes.values()) lista.add(montar(d, agora));
        return lista;
    }

    public synchronized FonteInfo info(String fonteId) {
        return montar(fontes.get(fonteId), System.currentTimeMillis());
    }

    /**
     * Critério de inatividade: sem nenhuma leitura há mais de TOLERANCIA_INATIVIDADE
     * intervalos anunciados pela própria fonte (a contagem começa na descoberta).
     */
    private FonteInfo montar(DescobertaResposta d, long agora) {
        String id = d.getFonteId();
        long ultima = ultimaLeitura.getOrDefault(id, 0L);
        long referencia = Math.max(ultima, descobertaEm.get(id));
        long intervalo = Math.max(1, intervaloMs.getOrDefault(id, d.getIntervaloMs()));
        boolean ativa = agora - referencia <= Config.TOLERANCIA_INATIVIDADE * intervalo;

        FonteInfo.Builder b = FonteInfo.newBuilder()
                .setFonteId(id).setTipo(d.getTipo()).setIp(d.getIp()).setPorta(d.getPorta())
                .setControlavel(d.getControlavel()).setRack(d.getRack())
                .setEstado(ativa ? EstadoFonte.ATIVA : EstadoFonte.INATIVA)
                .setUltimaLeituraMs(ultima);
        EstadoAr ar = estadoAr.get(id);
        if (ar != null) b.setEstadoAr(ar);
        return b.build();
    }
}
