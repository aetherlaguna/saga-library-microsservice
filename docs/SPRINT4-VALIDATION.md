# Sprint 4 — evidências de validação

Data: 26/10/2026. Branch: sprint/4-loan-results, criada a partir do estado local
concluído da Sprint 3. Sem push, PR ou execução sobre bancos de desenvolvimento.

## Testes automatizados

Java 25, PostgreSQL 17 e RabbitMQ 4.3.6, em infraestrutura descartável:

- loans: `./mvnw -B -o -Pintegration verify`, 61 testes aprovados, sem falhas,
  erros ou testes ignorados.
- catalog: mesmo comando, 44 testes aprovados, sem falhas ou testes ignorados.
- `python3 -m unittest discover -s scripts/tests`: quatro testes aprovados.

Novos cenários incluem codec e versão de contrato, hash canônico independente
da ordem dos livros/campos desconhecidos, envelope inválido e IDs repetidos;
ativação e recusa; correlação com outbox original; livro divergente; eventId
reutilizado; resultados contraditórios; duplicação simultânea; rollback de inbox,
decisão e status; limite de três tentativas para conflito otimista; ack após
commit e perda de ack sem novo efeito; consumidor parado/reiniciado com mensagem
pendente; JSON inválido e falha persistente no banco encaminhados à DLQ.

O teste do retry otimista verifica o limite com transações simuladas. A regressão
existente verifica @Version em PostgreSQL real; não houve teste determinístico
novo forçando um conflito externo durante o commit deste consumidor. O teste
de duplicação concorrente usa duas chamadas e transações reais.

## Smoke com imagens reais

Compose descartável `s4test-90b0829c`, com portas dinâmicas, bancos independentes
e credenciais de teste. Os dois Dockerfiles foram construídos.

1. Health dos dois serviços retornou 200.
2. POST /books criou um título com uma cópia disponível.
3. Primeiro POST /loans gerou PENDING e convergiu a ACTIVE, por eventos/outboxes.
4. Segundo POST /loans para o mesmo título convergiu a CANCELLED.
5. Estoque final de catalog permaneceu em zero; loans registrou duas inboxes e
   duas decisões, sem reservar mais que o disponível.
6. Serviços loans/catalog reiniciados; estado persistido conferido.
7. Stack descartável removida. Containers preexistentes e hash do .env preservados.

A aplicação de desenvolvimento não foi atualizada. V4 foi executada somente nos
ambientes descartáveis. O CI remoto ainda não foi executado para esta branch.

## Commits e limites

- 61b41c0: contrato/codec dos resultados e testes.
- 340f3e6: inbox/decisão e mudança de estado transacionais, com retry otimista.
- 4e73524: topologia, consumidor, ack/reject e testes de integração.
- Encerramento/documentação: commit que adiciona este relatório.

Timeout, compensação e liberação de reserva continuam na Sprint 5. Não há
políticas novas de elegibilidade, prazo, multas ou autenticação. Estado inesperado
ou resultado conflitante exige investigação/replay; não há liberação automática.
