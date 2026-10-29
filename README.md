# Gateway DCIM — monitoramento de data center

Trabalho de Sistemas Distribuídos (TI0151, UFC). Um **gateway** central descobre **fontes de dados** por multicast, recebe leituras por UDP, comanda a fonte controlável por gRPC e atende um **cliente** de linha de comando por TCP. Todas as mensagens são Protocol Buffers. Linguagem: Java 21.

## Arquitetura

```
                  TCP (Protobuf, quadro = 4 bytes de tamanho + corpo)
 [Cliente CLI] ─────────────────────────────────────────────────► [ GATEWAY ]
                                                                    │ ▲ ▲ ▲
        multicast 239.255.42.99:5007 "quem está aí?" ◄──────────────┘ │ │ │
        resposta unicast (tipo, IP, porta, estado) ───────────────────┘ │ │
                                                      UDP :5008 leituras │ │
 [Ar-condicionado]  gRPC :50051 ◄──── comandos do gateway ───────────────┘ │
 [Sensor temp. A/B] ─────────────── leituras + evento de limiar ───────────┤
 [PDU A/B]          ─────────────── consumo em watts ──────────────────────┘
```

| Canal | Transporte | Endereço | Mensagens (`src/main/proto/datacenter.proto`) |
|---|---|---|---|
| Descoberta (pedido) | UDP multicast | 239.255.42.99:5007 | `DescobertaSolicitacao` |
| Descoberta (resposta) | UDP unicast ao remetente do pedido | porta efêmera do gateway | `DescobertaResposta` |
| Leituras | UDP fonte → gateway | :5008 | `Leitura` (1 datagrama = 1 mensagem) |
| Comandos | gRPC gateway → ar-condicionado | :50051 | `ArCondicionado.Configurar` / `ObterEstado` |
| Cliente | TCP cliente → gateway | :5009 | `PedidoCliente` / `RespostaCliente` |

## Processos

| Classe | Papel |
|---|---|
| `Gateway` | Descoberta (repetida a cada 30 s), recepção UDP, servidor TCP (uma thread por cliente), cliente gRPC |
| `ArCondicionado` | Fonte **controlável**: servidor gRPC (liga/desliga, setpoint, ventilador, intervalo) + leituras UDP |
| `SensorTemperatura` | Fonte contínua por rack; envia a cada 2 s e **imediatamente** ao cruzar 27 °C (subindo ou descendo) |
| `Pdu` | Fonte contínua; consumo do rack em watts a cada 2 s |
| `Cliente` | CLI: listar, comandar, consultas agregadas |

Classes de apoio: `Cadastro` (estado do gateway, thread-safe), `DescobertaFonte` (lado da fonte na descoberta), `Quadro` (enquadramento TCP), `Rede`, `Config`.

## Requisitos e instalação

- Java 21 e Maven 3.8+ (o Maven baixa o `protoc` e o plugin gRPC sozinho; precisa de internet na primeira vez).
- Rede com multicast (Wi-Fi ou Ethernet). Teste feito em uma única máquina e em rede local.

```bash
mvn -q compile          # gera o código Protobuf/gRPC e compila
```

## Como executar a demonstração

Um terminal por processo, na pasta do projeto. **Suba as fontes antes do gateway** para a primeira descoberta já achar todas (fontes que subirem depois são achadas na rodada seguinte, em até 30 s).

```bash
./rodar.sh ArCondicionado ar-01                 # terminal 1  (gRPC na porta 50051)
./rodar.sh SensorTemperatura temp-rack-A A      # terminal 2  (digite q = esquentar, n = normal)
./rodar.sh SensorTemperatura temp-rack-B B      # terminal 3
./rodar.sh Pdu pdu-rack-A A                     # terminal 4  (q = pico de consumo, n = normal)
./rodar.sh Pdu pdu-rack-B B                     # terminal 5
./rodar.sh Gateway                              # terminal 6
./rodar.sh Cliente                              # terminal 7  (ou: ./rodar.sh Cliente <ip-do-gateway>)
```

Se a máquina tiver várias interfaces de rede, force uma: `JAVA_OPTS="-Ddc.interface=wlo1" ./rodar.sh Gateway` (use o mesmo valor nas fontes). Para repetir a descoberta mais rápido: `JAVA_OPTS="-Ddc.descoberta.s=10"`.

### Comandos do cliente

```
listar                          fontes, estado (ATIVA/INATIVA) e detalhe do ar-condicionado
ligar <id> | desligar <id>
setpoint <id> <graus>           10 a 35
ventilador <id> <0-100>
intervalo <id> <ms>             500 a 60000
consulta media|rack|pico [janela_s]
```

### Roteiro curto para reproduzir

1. `listar`: 5 fontes ATIVAS.
2. `desligar ar-01`, `setpoint ar-01 99` (recusado pela fonte, `INVALID_ARGUMENT`), `setpoint ar-01 20`, `ligar ar-01`.
3. No terminal do sensor A, digitar `q`: o gateway mostra `<<< EVENTO` na hora. Digitar `n`: novo evento (voltou abaixo do limiar).
4. No terminal da PDU B, digitar `q` (pico). Depois: `consulta media 10`, `consulta rack 10`, `consulta pico 10`.
5. **Falha:** encerrar o ar-condicionado (Ctrl+C). `setpoint ar-01 21` retorna `não respondeu (UNAVAILABLE)`. Em alguns segundos, `listar` mostra `ar-01 INATIVA`.

## Versão navegável (slides + terminais)

`docs/demo-online.html` reúne os 8 slides e os 7 terminais numa página só, com o mesmo visual dos slides (`docs/slides.pdf`, padrão UFC branco: fundo branco, brasão na capa, fonte Latin Modern). Tem dois modos:

- **Java real** (o que vale para a demonstração): a página controla os processos Java **deste computador**. Como o navegador não sobe processos, existe uma ponte local, a classe `Painel`:

  ```bash
  ./rodar.sh Painel          # na pasta do projeto; imprime o endereço e um token
  ```

  Abra **http://localhost:8099/**. A página já abre ligada ao Java real: "Subir tudo" (ou os comandos `./rodar.sh ...` digitados em cada terminal), os botões da faixa "Demonstração" de cada slide e o Ctrl+C de cada terminal rodam os mesmos processos das seções acima. Segurança: o painel escuta só em 127.0.0.1, exige o token sorteado a cada execução, confere o cabeçalho Host e só sobe as 5 classes do projeto com argumentos filtrados.
- **Simulação**: um modelo em JavaScript do sistema (mesmas mensagens, comandos e saídas; os bytes Protobuf conferem com os do Java), com controle de perda e duplicação de UDP. É o que aparece quando a página é aberta sem o painel, por exemplo no link público: https://justinocarneiro.github.io/gateway-dcim-sd/demo-online.html. Nessa página, "Java real" aceita o token do painel (campo ao lado do botão) e funciona em Chrome e Firefox; se o navegador bloquear (Safari), abra http://localhost:8099/ direto.

## Decisões de projeto

- **Domínio:** data center (DCIM). Unidades: °C e W. Limiar de 27 °C = limite superior da faixa **recomendada** (18–27 °C, entrada de ar, classes A1–A4) do ASHRAE TC 9.9, *Thermal Guidelines for Data Processing Environments*, 5ª ed. (2021); a faixa **permitida** da classe A1 vai de 15 a 32 °C.
- **Descoberta:** o pedido vai por multicast (o gateway não conhece os IPs); a resposta é unicast. O pedido leva o IP e a porta UDP do gateway, assim as fontes aprendem o destino das leituras sem configuração. O pedido é repetido a cada 30 s para achar fontes que subiram depois ou que perderam o pedido (UDP).
- **UDP sem tipo:** o datagrama Protobuf não traz o tipo da mensagem, então descoberta (porta efêmera) e leituras (porta 5008) usam sockets separados.
- **Enquadramento TCP:** `[4 bytes big-endian com o tamanho][Protobuf]`. Conteúdo inválido recebe resposta de erro e a conexão segue; tamanho fora do limite (1 MiB) fecha a conexão, porque o fluxo perdeu o alinhamento.
- **Relógio:** as janelas das consultas usam a hora de **chegada** no gateway, não o timestamp da fonte (relógios de máquinas diferentes não são comparáveis).
- **gRPC:** campos `optional` (ausente = não alterar), valores absolutos (comando **idempotente**: repetir é seguro), prazo de 3 s, erros por código de status.
- **Sequência das leituras:** cada fonte numera as leituras (`seq`); o gateway registra no log perda (`N leitura(s) perdida(s)`), duplicação e reordenação. `seq 0` indica fonte reiniciada.
- **Ar-condicionado desligado** continua enviando leituras com `ligado=false`: assim o gateway distingue "desligado por comando" de "caiu" (ausência de leituras).
- **Inatividade:** fonte sem leituras por mais de 3 intervalos anunciados é marcada INATIVA e volta a ATIVA na próxima leitura. Não distingue fonte caída de rede caída.
- **Consultas** (janela padrão 300 s): `media` = média das leituras dos sensores de rack; `rack` = rack com maior **média** de temperatura; `pico` = maior leitura de potência e a PDU que a gerou. Sem leituras na janela: "sem dados".
- **Histórico em memória**, limitado a 1 hora.

## Limitações

- O gateway é ponto único de falha; os dados se perdem ao reiniciá-lo.
- Sem persistência, sem interface gráfica, uma só linguagem (itens de pontuação extra não feitos).
- gRPC sem TLS (`usePlaintext`); sem autenticação no TCP.
