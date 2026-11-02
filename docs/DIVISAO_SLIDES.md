# Divisão dos slides para a arguição

Duas pessoas, 8 slides. **Marcos (M)** abre e fecha; **colega (C)** assume o miolo (UDP, gRPC, TCP e consultas, CSV).
A divisão segue o que cada um conhece melhor e deixa só duas passagens de palavra (slide 4 → 5 e slide 7 → 8).

| Slide | Título | Quem | Tempo |
|---|---|---|---|
| 1 | Título | M | 0:25 |
| 2 | Domínio e arquitetura | M | 0:45 |
| 3 | Linguagem, frameworks e bibliotecas | M | 0:15 |
| 4 | Descoberta (multicast) | M | 1:00 |
| 5 | Leituras (UDP) | **C** | 0:30 |
| 6 | Serviço gRPC do ar-condicionado | **C** | 1:00 |
| 7 | Cliente ↔ gateway, consultas e histórico (TCP + CSV) | **C** | 1:15 |
| 8 | Falhas, concorrência e limitações | M | 1:20 |

Total: M ≈ 3:45, C ≈ 2:45 (alvo do vídeo: 6:30; limite 7:00). Na arguição não há relógio: o que importa é cada um **saber defender o seu bloco**.

**Antes de tudo:** a capa (slide 1) hoje traz só o nome de Marcos. Incluir o nome e a matrícula do colega.

---

## Slide 1: Título (M)
Nome(s), matrícula(s), disciplina e o tema: sistema de monitoramento de data center com multicast, UDP, gRPC e TCP, tudo em Protobuf.

## Slide 2: Domínio e arquitetura (M)
- **Domínio:** data center (DCIM). O gateway reúne as fontes; o operador consulta e comanda pelo cliente.
- Apontar as **4 setas** do diagrama: TCP (cliente ↔ gateway), gRPC (gateway → ar-condicionado), UDP (fontes → gateway) e multicast (descoberta).
- 5 fontes na demonstração: 1 ar-condicionado (controlável), 2 sensores de temperatura, 2 PDUs. Limiar de 27 °C (ASHRAE). Tudo em Protobuf.
- **Por quê:** cada canal foi escolhido pelo que o dado precisa (leitura frequente → UDP; comando confiável → gRPC; cliente → TCP; "quem está aí?" → multicast).

## Slide 3: Tecnologias (M)
- Java 21; Maven; Protobuf 3.25.5; gRPC 1.68.0; sockets da biblioteca padrão.
- **Por quê Java:** uma linguagem só, sockets/threads/multicast na biblioteca padrão, suporte maduro a Protobuf e gRPC, reaproveita o trabalho anterior.
- **Por quê Protobuf:** compacto, contrato único (`datacenter.proto`), código gerado para todos os processos.

## Slide 4: Descoberta (M)
- O gateway não conhece IPs: **pergunta ao grupo multicast** `239.255.42.99:5007`; cada fonte responde por **unicast**.
- Mostrar os campos de `DescobertaSolicitacao` e `DescobertaResposta`; o número do campo é o que vai no fio.
- O pedido leva o **IP e a porta UDP do gateway**: a fonte aprende para onde enviar, sem configuração.
- **Nonce** evita confundir respostas de rodadas diferentes. Pedido **repetido a cada 30 s** (UDP perde; fontes podem subir depois).
- Código: `Gateway.descobrir()` e `DescobertaFonte.run()`.

## Slide 5: Leituras (C)
- Um datagrama UDP = uma mensagem `Leitura`, enviada à porta 5008 do gateway.
- **Periódico** a cada 2 s e **evento imediato** ao cruzar 27 °C (sobe e desce), com `evento = true`.
- UDP pode perder, duplicar e reordenar: o `seq` permite ao gateway **detectar e registrar** isso.
- O ar-condicionado continua enviando quando desligado (distingue "desligado" de "caiu").
- O gateway usa a **hora de chegada**, não o `timestamp_ms` da fonte (relógios diferentes).
- Código: `SensorTemperatura.enviarLeituras()`, `Gateway.receberLeituras()`, `Cadastro.registrarLeitura()`.

## Slide 6: Serviço gRPC do ar-condicionado (C)
- A fonte controlável é o **servidor**; o gateway é o **cliente**. Serviço `ArCondicionado` com `Configurar` e `ObterEstado`.
- `ConfigurarRequest` com campos `optional`: **atualização parcial** (ausente = não alterar). Valores absolutos: o comando é **idempotente**.
- **Validação na fonte:** valor fora da faixa devolve `INVALID_ARGUMENT`, repassado ao cliente. **Prazo de 3 s** por chamada; fonte fora do ar devolve `UNAVAILABLE`.
- Demonstração: `setpoint ar-01 99` (recusa), `setpoint ar-01 20`, `desligar ar-01`, `ligar ar-01`.
- Código: `Gateway.comandar()`, `ArCondicionado.validar()/aplicar()/Servico.configurar()`.

## Slide 7: Cliente ↔ gateway, consultas e histórico (C)
- TCP é um **fluxo de bytes**: cada mensagem vai com **4 bytes de tamanho** na frente. Limite de 1 MiB; conteúdo inválido não derruba a conexão.
- `PedidoCliente` / `RespostaCliente` com `oneof`: listar, comando, consulta, histórico.
- **3 consultas** (janela padrão 300 s): `media`, `rack`, `pico`. Sem dados na janela: `tem_dados = false`.
- **Histórico em CSV:** o gateway grava cada leitura em `data/leituras.csv`; `historico [id] [n]` lê o arquivo; ao reiniciar, a última hora volta para a memória (as consultas continuam valendo).
- Demonstração: `listar`, sensor A `q`, PDU B `q`, `consulta media 10`, `consulta rack 10`, `consulta pico 10`, `historico 5`; reiniciar o gateway e repetir.
- Código: `Quadro`, `Cadastro.consultar()`, `HistoricoCsv`, `Gateway.historico()`.

## Slide 8: Falhas, concorrência e limitações (M)
- **Falha:** fonte sem leitura por mais de **3 intervalos** → `INATIVA` (volta a `ATIVA` na próxima leitura). Comando a fonte caída → `UNAVAILABLE` e o gateway segue.
- Demonstração: matar o ar-condicionado, `setpoint ar-01 21` (`UNAVAILABLE`), esperar ~7 s, `listar` (`ar-01 INATIVA`).
- **Concorrência:** thread UDP, thread TCP (uma por cliente) e agenda de descoberta; estado compartilhado em `Cadastro` (`synchronized`).
- **Limitações (admitir):** gateway é ponto único de falha; sem TLS nem autenticação; o detector por tempo não distingue fonte caída de rede caída; CSV cresce sem limite.

---

## Como se preparar (cada um)
- Ler a seção do **seu** bloco no `docs/guia-do-codigo.pdf` (classe por classe) e a seção 8 (perguntas prováveis).
- Abrir e percorrer os arquivos do seu bloco no editor, para saber mostrar onde está cada coisa.
- Ensaiar a demonstração do seu bloco **uma vez, junto com o outro**, na rede da sala.
- Perguntas da arguição são sobre o **porquê** das decisões; qualquer um dos dois deve saber responder o básico dos outros blocos (ler o guia inteiro uma vez).
