package com.shifa.oms.chatbot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A question typed into the Shifa assistant. */
public record AskRequest(
        @NotBlank(message = "Please type a question.")
        @Size(max = 300, message = "Please keep the question under 300 characters.")
        String question) {
}
