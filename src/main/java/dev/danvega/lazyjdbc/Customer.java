package dev.danvega.lazyjdbc;

import org.springframework.data.annotation.Id;

public record Customer(@Id Long id, String name) {}
