package dev.danvega.lazyjdbc;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CustomerController {

    private final CustomerRepository repository;

    CustomerController(CustomerRepository repository) {
        this.repository = repository;
    }

    // Opens a transaction, runs a real query. The control case.
    @GetMapping("/with-db")
    @Transactional
    public long withDb() {
        return repository.count();
    }

    // Opens a transaction, returns without touching the database.
    // Stands in for a cache hit or an early return.
    @GetMapping("/no-db")
    @Transactional
    public String noDb() {
        return "did no database work";
    }
}
