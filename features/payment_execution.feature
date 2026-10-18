# language: es
@aggregate:PaymentExecution @bian:PaymentExecution @iso20022:pacs.008 @capability:L3
Característica: Ejecución de pagos entre cuentas (BIAN Payment Execution)
  Como Debtor de una cuenta corriente
  Quiero ejecutar una transferencia hacia un Creditor
  Para que el pago se liquide una sola vez y quede trazado con ISO 20022

  Antecedentes:
    Dado que el Debtor tiene una cuenta activa con saldo de 500 COP

  Escenario: Ejecución de un pago aceptado
    Dado una orden de pago por 120 COP hacia un Creditor válido
    Cuando el Debtor ejecuta el pago con la llave de idempotencia "pay-001"
    Entonces el pago queda aceptado
    Y se publica un evento PaymentExecuted

  Escenario: Solicitud duplicada con la misma llave de idempotencia
    Dado un pago ya ejecutado con la llave de idempotencia "pay-001"
    Cuando el Debtor ejecuta el pago dos veces con la misma llave de idempotencia
    Entonces solo existe un movimiento de 120 COP
    Y la segunda respuesta repite el resultado de la primera

  Escenario: Solicitud sin un token de acceso válido
    Dado una orden de pago por 50 COP hacia un Creditor válido
    Cuando el Debtor ejecuta el pago sin un token de acceso válido
    Entonces el pago es rechazado con HTTP 401 por falta de autorización

  Escenario: Pago rechazado por fondos insuficientes
    Dado una orden de pago por 900 COP hacia un Creditor válido
    Cuando el Debtor ejecuta el pago con la llave de idempotencia "pay-002"
    Entonces el pago es rechazado con HTTP 422
