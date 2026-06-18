package dev.danvega.lazyjdbc;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RegistrationController {

    private final RegistrationService registrationService;

    RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    // Slow call then one insert. The transaction lives on the service; this just delegates.
    @GetMapping("/register/{userId}")
    Customer register(@PathVariable long userId) {
        return registrationService.register(userId);
    }
}
