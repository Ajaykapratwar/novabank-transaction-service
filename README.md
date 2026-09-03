# NovaBank Transaction Service

NovaBank Transaction Service is a Spring Boot microservice for creating and retrieving banking transactions. It supports deposits, account-to-account transfers, withdrawals, transaction history, JWT authentication, service discovery, and asynchronous account-balance updates through Kafka.

## Features

- Deposit funds into an account
- Transfer funds between NovaBank accounts
- Withdraw funds from an account
- Retrieve a transaction by reference
- Retrieve account history for a date range or transaction direction
- Validate accounts through the User Account Service using OpenFeign
- Publish balance updates to Kafka
- Stateless JWT authentication and role-based authorization
- Health and operational endpoints through Spring Boot Actuator

## Technology stack

- Java 21
- Spring Boot 4.1.1
- Spring Web MVC
- Spring Data JPA and PostgreSQL
- Spring Security and JSON Web Tokens (JJWT)
- Spring Cloud OpenFeign and Eureka Discovery Client
- Apache Kafka
- Maven
- Docker

## Service dependencies

The service expects the following infrastructure to be available:

| Dependency | Default address | Purpose |
| --- | --- | --- |
| PostgreSQL | Configured with `DB_URL_TRANSACTION` | Transaction persistence |
| Eureka Server | `http://localhost:8761/eureka/` | Service discovery |
| Kafka | `localhost:9092` | Balance update events |
| User Account Service | Eureka service name `user-account-service` | Account lookup and validation |

The account service must expose:

```text
GET /api/accounts/{accountNumber}
```

## Getting started

### Prerequisites

- JDK 21 or later
- Maven 3.9+ (or use the included Maven Wrapper)
- PostgreSQL
- Kafka
- Eureka Server
- User Account Service

### Configuration

The application imports an optional `.env` file from the project root. Create a local `.env` file or provide equivalent environment variables:

```properties
DB_URL_TRANSACTION=jdbc:postgresql://localhost:5432/novabank_transaction_service_db
DB_USERNAME=postgres
DB_PASSWORD=change-me
JWT_SECRET=replace-with-a-long-random-secret
```

Do not commit real credentials or JWT secrets. The repository's local environment file should remain private.

Default application settings are:

```text
Application name: transaction-service
HTTP port:       8082
Eureka server:   http://localhost:8761/eureka/
Kafka broker:    localhost:9092
Kafka topic:     balance-update-events
```

### Run locally

On Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

On macOS or Linux:

```bash
./mvnw spring-boot:run
```

The service starts at `http://localhost:8082`.

### Build and test

```bash
./mvnw clean verify
```

On Windows, use `.\mvnw.cmd clean verify`.

### Run with Docker

Build the image:

```bash
docker build -t novabank-transaction-service .
```

Run the container with configuration supplied at runtime:

```bash
docker run --rm -p 8082:8082 \
  -e DB_URL_TRANSACTION="jdbc:postgresql://host.docker.internal:5432/novabank_transaction_service_db" \
  -e DB_USERNAME="postgres" \
  -e DB_PASSWORD="change-me" \
  -e JWT_SECRET="replace-with-a-long-random-secret" \
  novabank-transaction-service
```

## Authentication

All transaction endpoints require a valid JWT in the `Authorization` header:

```http
Authorization: Bearer <jwt-token>
```

The token subject is treated as the authenticated user's email. Administrative endpoints additionally require the `ADMIN` authority.

Actuator endpoints under `/actuator/**` are publicly accessible by the current security configuration. Restrict them appropriately before exposing the service outside a trusted network.

## API reference

Base URL: `http://localhost:8082`

### Customer transaction endpoints

| Method | Endpoint | Description |
| --- | --- | --- |
| `POST` | `/api/transactions/transfer` | Transfer funds between two accounts |
| `POST` | `/api/transactions/withdraw` | Withdraw funds from an account |
| `GET` | `/api/transactions/history?accountNumber={accountNumber}` | Retrieve account history |
| `GET` | `/api/transactions/history?accountNumber={accountNumber}&start={yyyy-MM-dd}&end={yyyy-MM-dd}` | Retrieve history within an inclusive date range |
| `GET` | `/api/transactions/history/direction?accountNumber={accountNumber}&direction={DEBIT\|CREDIT}` | Retrieve debit or credit history |
| `GET` | `/api/transactions/reference/{reference}` | Retrieve one transaction by reference |

### Administrative endpoints

These endpoints require the `ADMIN` authority:

| Method | Endpoint | Description |
| --- | --- | --- |
| `POST` | `/api/transactions/admin/deposit` | Deposit funds into an account |
| `GET` | `/api/transactions/admin/history/{accountNumber}` | Retrieve all transactions for an account |

### Request body

Transfer, withdrawal, and deposit requests use the following JSON shape:

```json
{
  "fromAccountNumber": "1000000001",
  "toAccountNumber": "1000000002",
  "amount": 125.50,
  "description": "Payment"
}
```

`toAccountNumber` and `amount` are required. The amount must be at least `0.01`. Transfers and withdrawals also require a source account.

For a deposit, `toAccountNumber` identifies the account being credited. For a withdrawal, `fromAccountNumber` identifies the account being debited.

### Example request

```bash
curl -X POST http://localhost:8082/api/transactions/transfer \
  -H "Authorization: Bearer <jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "fromAccountNumber": "1000000001",
    "toAccountNumber": "1000000002",
    "amount": 125.50,
    "description": "Payment"
  }'
```

### Response format

Successful responses use this envelope:

```json
{
  "statusCode": 201,
  "message": "Transfer Successful",
  "data": {
    "id": 1,
    "reference": "TRF12345678",
    "fromAccountNumber": "1000000001",
    "toAccountNumber": "1000000002",
    "amount": 125.50,
    "currency": "USD",
    "transactionType": "TRANSFER",
    "transactionStatus": "SUCCESS",
    "channel": "API",
    "createdAt": "2026-01-15T12:30:00"
  }
}
```

Supported currencies currently include `USD`, `EUR`, and `NGN`. Transaction types currently include `DEPOSIT` and `TRANSFER`; directions are `CREDIT` and `DEBIT`.

## Events

After a successful transaction, the service publishes a JSON balance update event to the Kafka topic `balance-update-events`. The User Account Service consumes these events to apply account balance changes.

Transfers publish two balance updates:

- `DEBIT` for the source account
- `CREDIT` for the destination account

## Project structure

```text
src/main/java/com/example/transactionservice/
|-- controller/       HTTP endpoints
|-- dto/              Request and response models
|-- entity/           JPA transaction entity
|-- enums/            Transaction and account-related enums
|-- exceptions/       API exception handling
|-- feign/            User Account Service client
|-- kafka/            Balance update event publishing
|-- repository/       Transaction persistence
|-- security/         JWT authentication and authorization
`-- service/          Transaction business logic
```

## Health checks

With the default configuration, Actuator endpoints are available under:

```text
GET http://localhost:8082/actuator/health
```

## License

See [LICENSE](LICENSE).
