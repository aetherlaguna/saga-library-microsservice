# Catalog

Serviço de títulos, estoque e reserva atômica da biblioteca.

[Modelo, API, mensageria, execução e testes](../docs/CATALOG.md).

```sh
./mvnw clean test
./mvnw -Pintegration clean verify
```

Integração exige Docker e usa bancos/broker descartáveis, sem `.env` de desenvolvimento.
