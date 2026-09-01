package com.example.transactionservice.feign;

import com.example.transactionservice.dto.AccountDTO;
import com.example.transactionservice.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "user-account-service") //this resiles soly on the discovery service to access the acount service
//@FeignClient(name = "user-account-service", url = "http://localhost:8081")// this make an api call directly to the account service without passing through the discovery service
public interface AccountFeignClient {

    @GetMapping("/api/accounts/{accountNumber}")
    ApiResponse<AccountDTO> getAccountByNumber(@PathVariable("accountNumber") String accountNumber);
}
