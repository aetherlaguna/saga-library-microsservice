# Sprint 1 — validação operacional

Execução em 14/10/2026 UTC. Escopo: credencial incorreta com recuperação e
persistência do RabbitMQ após recriação do container. Estas operações foram
executadas uma vez no ambiente local e não fazem parte do workflow de CI.

## Autenticação e recuperação

| Etapa | Evidência |
|---|---|
| Configuração original | Health global/db/rabbit UP; conexão AMQP running |
| Senha aleatória incorreta apenas em loans | HTTP 503; db UP; rabbit DOWN; diagnóstico com saída 1 |
| Configuração restaurada | HTTP 200; db/rabbit UP; diagnóstico com saída 0 |
| Preservação | Hash do `.env` e ID do container do broker inalterados |

Foi usado um override Compose privado e temporário, fora do repositório,
sobrescrevendo somente `SPRING_RABBITMQ_PASSWORD` de loans. O hash da configuração
do broker permaneceu igual, enquanto o de loans mudou. A restauração ocorreu
em bloco `finally`, seguida de verificação com prazo limitado.
Não foram alterados usuários, permissões ou senhas do broker nesse teste.

## Persistência do broker

1. Conferimos hostname, nó ativo, volume e imagem existentes. A tag local da
   imagem apontava para o mesmo ID usado pelo container; não houve pull/upgrade.
2. Paramos loans para evitar tráfego durante as comparações.
3. Criamos uma fila temporária exclusiva, durável, com uma mensagem persistente
   (`delivery_mode=2`) e payload único. A publicação confirmou roteamento.
4. Registramos usuários, permissões, vhosts, exchanges, bindings, filas e contagens.
5. Paramos o broker com prazo de 60 segundos, confirmando encerramento com código 0.
6. Geramos snapshot integral do volume usando a mesma imagem em container
   temporário sem rede e com o volume montado somente para leitura.
7. Validamos leitura do arquivo compactado e registramos SHA-256. Diretório
   privado com permissão 0700; backup e metadados com permissão 0600.
8. Recriamos somente rabbitmq, sem pull, mantendo hostname e volume existentes.
9. Confirmamos novo ID de container e os mesmos IDs de imagem e volume, hostname
   e nome do nó. Todos os metadados comparados permaneceram iguais.
10. Recuperamos a mensagem da fila de teste e conferimos payload e persistência.
    Removemos somente essa fila e confirmamos retorno aos metadados anteriores.
11. Iniciamos loans e confirmamos o diagnóstico AMQP aprovado.

O ID do container PostgreSQL e o hash do `.env` permaneceram inalterados.
Os dois diretórios de nós existentes no volume foram preservados. Não houve
remoção de volume, restauração destrutiva ou alteração de migrations.

O snapshot contém dados privados do broker, incluindo cookie e hashes de
credenciais: foi mantido fora do Git, no diretório temporário privado informado
na execução. Não compartilhar esse arquivo nem incluí-lo em artefatos de CI.
O backup foi verificado por leitura e checksum; restauração não foi necessária
nem testada. Não apagar/restaurar um volume para repetir esta verificação.

A API HTTP foi usada apenas para a fila temporária de diagnóstico, sem introduzir
publicação de eventos na aplicação. Referência:
[API HTTP oficial do RabbitMQ](https://www.rabbitmq.com/docs/http-api-reference).

## Encerramento local

Os testes automatizados da etapa anterior passaram: 24 Java e 4 Python,
sem falhas ou testes ignorados. Código/testes: `e3eee79` e `dbd431c`.
Documentação dos contratos: `2162471`.

Com estas evidências, todos os itens locais da checklist da Sprint 1 estão
concluídos. O usuário confirmou o CI anterior; os novos commits ainda precisam
ser enviados ao GitHub e aprovados pelo workflow correspondente.
