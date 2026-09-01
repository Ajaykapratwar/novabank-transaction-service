package com.example.transactionservice.service.impl;

import com.example.transactionservice.dto.AccountDTO;
import com.example.transactionservice.dto.ApiResponse;
import com.example.transactionservice.dto.TransactionDTO;
import com.example.transactionservice.dto.TransactionRequest;
import com.example.transactionservice.entity.Transaction;
import com.example.transactionservice.enums.*;
import com.example.transactionservice.exceptions.BadRequestException;
import com.example.transactionservice.exceptions.NotFoundException;
import com.example.transactionservice.feign.AccountFeignClient;
import com.example.transactionservice.kafka.dto.BalanceUpdateEvent;
import com.example.transactionservice.kafka.service.TransactionEventPublisher;
import com.example.transactionservice.repository.TransactionRepository;
import com.example.transactionservice.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionServiceImpl implements TransactionService {


    private final TransactionRepository transactionRepository;
    private final AccountFeignClient accountFeignClient;
    private final ModelMapper modelMapper;
    private final TransactionEventPublisher transactionEventPublisher;


    @Override
    @Transactional
    public ApiResponse<TransactionDTO> deposit(TransactionRequest request) {

        fetchAndValidateAccount(request.getToAccountNumber());

        Transaction deposit = Transaction.builder()
                .reference("DEP" + UUID.randomUUID().toString().substring(0, 8))
                .fromAccountNumber(request.getFromAccountNumber())
                .fromBankCode("NOVA")
                .currency(Currency.USD)
                .toAccountNumber(request.getToAccountNumber())
                .toBankCode("NOVA")
                .amount(request.getAmount())
                .transactionDirection(TransactionDirection.CREDIT)
                .channel(Channel.API)
                .description(request.getDescription())
                .transactionType(TransactionType.DEPOSIT)
                .transactionStatus(TransactionStatus.SUCCESS)
                .createdAt(LocalDateTime.now())
                .build();

        Transaction savedTransaction = transactionRepository.save(deposit);

        //notify the account service to update the user account balance
        BalanceUpdateEvent balanceUpdateEvent = BalanceUpdateEvent.builder()
                .accountNumber(request.getToAccountNumber())
                .amount(request.getAmount())
                .currency(Currency.USD)
                .description(request.getDescription())
                .transactionDirection(TransactionDirection.CREDIT)
                .transactionStatus(TransactionStatus.SUCCESS)
                .reference(savedTransaction.getReference())
                .build();

        transactionEventPublisher.sendBalanceUpdate(balanceUpdateEvent);

        return new ApiResponse<>(
                201,
                "Deposit Successful",
                modelMapper.map(savedTransaction, TransactionDTO.class));
    }

    @Override
    public ApiResponse<TransactionDTO> transfer(TransactionRequest request) {

        if (request.getFromAccountNumber() == null || request.getFromAccountNumber().isEmpty()) {
            throw new BadRequestException("From Account is Needed");
        }
        AccountDTO sourceAccount = fetchAndValidateAccount(request.getFromAccountNumber());

        String loggedInUserEmail = null;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.isAuthenticated()) {
            loggedInUserEmail = authentication.getName();
        }
        log.info("Auth email is: {}", loggedInUserEmail);
        log.info("Account email is: {}", sourceAccount.getOwnerEmail());

        if (!sourceAccount.getOwnerEmail().equals(loggedInUserEmail)) {
            throw new BadRequestException("Access Denied: You are not authorized to perform a transfer on behalf of another person");
        }

        if (sourceAccount.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new BadRequestException("Transaction Failed: Your account is inactive, please contact customer support");
        }

        if (sourceAccount.getBalance().compareTo(request.getAmount()) < 0) {
            throw new BadRequestException("Insufficient Account Balance");
        }

        if (request.getFromAccountNumber().equals(request.getToAccountNumber())) {
            throw new BadRequestException("You cannot transfer to the same account number(Yourself)");
        }

        //VALIDATE THE DESTINATION BANK ACCOUNT
        fetchAndValidateAccount(request.getToAccountNumber());

        Transaction transferTnx = Transaction.builder()
                .reference("TRF" + UUID.randomUUID().toString().substring(0, 8))
                .fromAccountNumber(request.getFromAccountNumber())
                .fromBankCode("NOVA")
                .currency(Currency.USD)
                .toAccountNumber(request.getToAccountNumber())
                .toBankCode("NOVA")
                .amount(request.getAmount())
                .channel(Channel.API)
                .description(request.getDescription())
                .transactionType(TransactionType.TRANSFER)
                .transactionStatus(TransactionStatus.SUCCESS)
                .createdAt(LocalDateTime.now())
                .build();

        Transaction savedTransaction = transactionRepository.save(transferTnx);

        //NOTIFY ACCOUNT SERVICE: TO DEBIT THE SENDER
        transactionEventPublisher.sendBalanceUpdate(BalanceUpdateEvent.builder()
                .accountNumber(request.getFromAccountNumber())
                .amount(request.getAmount())
                .currency(Currency.USD)
                .description(request.getDescription())
                .transactionDirection(TransactionDirection.DEBIT)
                .transactionStatus(TransactionStatus.SUCCESS)
                .reference(savedTransaction.getReference())
                .build());


        //NOTIFY ACCOUNT SERVICE: TO CREDIT THE RECEIVER
        transactionEventPublisher.sendBalanceUpdate(BalanceUpdateEvent.builder()
                .accountNumber(request.getToAccountNumber())
                .amount(request.getAmount())
                .currency(Currency.USD)
                .description(request.getDescription())
                .transactionDirection(TransactionDirection.CREDIT)
                .transactionStatus(TransactionStatus.SUCCESS)
                .reference(savedTransaction.getReference())
                .build());

        return new ApiResponse<>(
                201,
                "Transfer Successful",
                modelMapper.map(savedTransaction, TransactionDTO.class)
        );

    }

    @Override
    public ApiResponse<TransactionDTO> withdraw(TransactionRequest request) {

        AccountDTO account = fetchAndValidateAccount(request.getFromAccountNumber());

        if (account.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new BadRequestException("Inactive Account");
        }

        if (account.getBalance().compareTo(request.getAmount()) < 0) {
            throw new BadRequestException("Insufficient Fund");
        }


        Transaction withdrawalTxn = Transaction.builder()
                .reference("WID" + UUID.randomUUID().toString().substring(0, 8))
                .fromAccountNumber(request.getFromAccountNumber())
                .fromBankCode("NOVA")
                .currency(Currency.USD)
                .toAccountNumber("VULT")
                .toBankCode("VULT")
                .amount(request.getAmount())
                .channel(Channel.API)
                .description(request.getDescription())
                .transactionType(TransactionType.TRANSFER)
                .transactionStatus(TransactionStatus.SUCCESS)
                .transactionDirection(TransactionDirection.DEBIT)
                .createdAt(LocalDateTime.now())
                .build();

        Transaction savedWithdrawalTnx = transactionRepository.save(withdrawalTxn);


        //NOTIFY ACCOUNT SERVICE: TO DEBIT THE ACCOUNT
        transactionEventPublisher.sendBalanceUpdate(BalanceUpdateEvent.builder()
                .accountNumber(request.getFromAccountNumber())
                .amount(request.getAmount())
                .currency(Currency.USD)
                .description(request.getDescription())
                .transactionDirection(TransactionDirection.DEBIT)
                .transactionStatus(TransactionStatus.SUCCESS)
                .reference(savedWithdrawalTnx.getReference())
                .build());

        return new ApiResponse<>(
                201,
                "Withdrawal Successful",
                modelMapper.map(savedWithdrawalTnx, TransactionDTO.class)
        );

    }

    @Override
    public ApiResponse<TransactionDTO> getTransactionByReference(String reference) {

        log.info("reference is: {}", reference);

        Transaction txn = transactionRepository.findByReference(reference)
                .orElseThrow(()-> new NotFoundException("Transaction Not Found"));

        TransactionDTO dto = modelMapper.map(txn, TransactionDTO.class);

        return new ApiResponse<>(
                201,
                "Transaction Retrieved",
                dto
        );

    }

    @Override
    public ApiResponse<List<TransactionDTO>> getAllTransactionHistoryOfAnAccountNumber(String accountNumber) {

        List<Transaction> transactionList = transactionRepository.findAllByAccountNumber(accountNumber);

        log.info("transaction history count is {}", (long) transactionList.size());

        List<TransactionDTO> transactionDTOS = transactionList.stream().map(t-> modelMapper.map(t, TransactionDTO.class)).toList();

        return new ApiResponse<>(
                201,
                "Transaction History Retrieved for the Account",
                transactionDTOS
        );

    }

    @Override
    public ApiResponse<List<TransactionDTO>> getTransactionHistory(String accountNumber, LocalDateTime start, LocalDateTime end) {

        List<Transaction> history = transactionRepository.findAllAccountNumberAndDateRange(accountNumber, start, end);

        List<TransactionDTO> transactionDTOS = history.stream().map(t-> modelMapper.map(t, TransactionDTO.class)).toList();

        return new ApiResponse<>(
                201,
                "Transaction History Retrieved for the Account",
                transactionDTOS
        );
    }

    @Override
    public ApiResponse<List<TransactionDTO>> getMyTransactionHistoryByDirection(String accountNumber, TransactionDirection direction) {

        List<Transaction> transactions = direction.equals(TransactionDirection.DEBIT) ?
                transactionRepository.findByFromAccountNumber(accountNumber) :
                transactionRepository.findByToAccountNumber(accountNumber);


        List<TransactionDTO> transactionDTOS = transactions.stream().map(t-> modelMapper.map(t, TransactionDTO.class)).toList();

        return new ApiResponse<>(
                201,
                "Transaction History Retrieved by direction for the Account",
                transactionDTOS
        );

    }




    private AccountDTO fetchAndValidateAccount(String accountNumber) {

        ApiResponse<AccountDTO> response = accountFeignClient.getAccountByNumber(accountNumber);

        if (response == null || response.data() == null) {
            throw new NotFoundException("Account " + accountNumber + "not found");
        }

        AccountDTO account = response.data();

        if (account.getAccountStatus().equals(AccountStatus.CLOSED)) {
            throw new BadRequestException("Transaction Denied: Account is Closed");
        }

        return account;

    }


}