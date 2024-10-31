package br.com.microservices.choreography.paymentservice.core.service;

import br.com.microservices.choreography.paymentservice.config.exception.ValidationException;
import br.com.microservices.choreography.paymentservice.core.dto.Event;
import br.com.microservices.choreography.paymentservice.core.dto.History;
import br.com.microservices.choreography.paymentservice.core.dto.OrderProducts;
import br.com.microservices.choreography.paymentservice.core.enums.EnumPaymentStatus;
import br.com.microservices.choreography.paymentservice.core.model.Payment;
import br.com.microservices.choreography.paymentservice.core.producer.KafkaProducer;
import br.com.microservices.choreography.paymentservice.core.repository.PaymentRepository;
import br.com.microservices.choreography.paymentservice.core.saga.SagaExecutionController;
import br.com.microservices.choreography.paymentservice.core.utils.JsonUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

import static br.com.microservices.choreography.paymentservice.core.enums.EnumSagaStatus.*;

@Slf4j
@Service
@AllArgsConstructor
public class PaymentService {

    private static final String CURRENT_SOURCE = "PAYMENT_SERVICE";
    private static final Double REDUCE_SUM_VALUE = 0.0;
    private static final Double MIN_AMOUNT_VALUE = 0.01;

    private final PaymentRepository paymentRepository;
    private final SagaExecutionController sagaExecutionController;

    public void realizePayment(Event event) {
        try {
            checkCurrentValidation(event);
            createPendingPayment(event);
            var payment = findByOrderIdAndTransactionId(event);
            validateAmount(payment.getTotalAmount());
            changePaymentToSuccess(payment);
            handleSuccess(event);

        } catch (Exception e) {
            log.error("Error trying to make payment: ", e);
            handleFailCurrentNotExecuted(event, e.getMessage());
        }

        sagaExecutionController.handleSaga(event);
    }

    private void checkCurrentValidation(Event event) {
        if (paymentRepository.existsByOrderIdAndTransactionId(
                event.getPayload().getId(), event.getTransactionId())) {
            throw new ValidationException("There's another transactionId for this validation.");
        }
    }

    private void createPendingPayment(Event event) {
        var totalAmount = calculateAmount(event);
        var totalItems = calculateTotalItems(event);
        var payment = Payment
                .builder()
                .orderId(event.getPayload().getId())
                .transactionId(event.getTransactionId())
                .totalAmount(totalAmount)
                .totalItems(totalItems)
                .build();
        save(payment);
        setEventAmountItems(event, payment);
    }

    private void save(Payment payment) {
        paymentRepository.save(payment);
    }

    private double calculateAmount(Event event) {
        return event
                .getPayload()
                .getProducts()
                .stream()
                .map(product -> product.getQuantity() * product.getProduct().getUnitValue())
                .reduce(REDUCE_SUM_VALUE, Double::sum);
    }

    private int calculateTotalItems(Event event) {
        return event
                .getPayload()
                .getProducts()
                .stream()
                .map(OrderProducts::getQuantity)
                .reduce(REDUCE_SUM_VALUE.intValue(), Integer::sum);
    }

    private void setEventAmountItems(Event event, Payment payment) {
        event.getPayload().setTotalAmount(payment.getTotalAmount());
        event.getPayload().setTotalItems(payment.getTotalItems());
    }

    private Payment findByOrderIdAndTransactionId(Event event) {
        return paymentRepository
                .findByOrderIdAndTransactionId(event.getPayload().getId(), event.getTransactionId())
                .orElseThrow(() -> new ValidationException("Payment not found dy OrderID and TransactionID"));

    }

    private void validateAmount(double amount) {
        if (amount < MIN_AMOUNT_VALUE) {
            throw new ValidationException("The mininum amount available is ".concat(MIN_AMOUNT_VALUE.toString()));
        }
    }

    private void changePaymentToSuccess(Payment payment) {
        payment.setStatus(EnumPaymentStatus.SUCCESS);
        save(payment);
    }

    private void handleSuccess(Event event) {
        event.setStatus(SUCCESS);
        event.setSource(CURRENT_SOURCE);
        addHistory(event, "Payment realized successfully!");
    }

    private void addHistory(Event event, String message) {
        var history = History
                .builder()
                .source(event.getSource())
                .status(event.getStatus())
                .message(message)
                .createdAt(LocalDateTime.now())
                .build();

        event.addToHistory(history);
    }

    private void handleFailCurrentNotExecuted(Event event, String message) {
        event.setStatus(ROLLBACK_PENDING);
        event.setSource(CURRENT_SOURCE);
        addHistory(event, "Fail to realize payment. ".concat(message));
    }

    public void realizeRefund(Event event) {
        event.setStatus(FAIL);
        event.setSource(CURRENT_SOURCE);

        try {
            changePaymentStatusToRefund(event);
            addHistory(event, "Rollback executed for payment!");
        } catch (Exception e) {
            addHistory(event, "Rollback not executed for payment ".concat(e.getMessage()));
        }

        sagaExecutionController.handleSaga(event);
    }

    private void changePaymentStatusToRefund(Event event) {
        var payment = findByOrderIdAndTransactionId(event);
        payment.setStatus(EnumPaymentStatus.REFUND);
        setEventAmountItems(event, payment);
        save(payment);

    }
}
