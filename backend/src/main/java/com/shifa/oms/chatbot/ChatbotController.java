package com.shifa.oms.chatbot;

import com.shifa.oms.chatbot.dto.AskRequest;
import com.shifa.oms.chatbot.dto.ChatbotAnswer;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Shifa assistant: {@code POST /api/admin/chatbot/ask}. ADMIN only, because
 * the answers cover company-wide and per-person sales. Read-only: it never
 * changes data, and it calls no external service.
 */
@RestController
@RequestMapping("/api/admin/chatbot")
@PreAuthorize("hasRole('ADMIN')")
public class ChatbotController {

    private final ChatbotService service;

    public ChatbotController(ChatbotService service) {
        this.service = service;
    }

    @PostMapping("/ask")
    public ChatbotAnswer ask(@Valid @RequestBody AskRequest request) {
        return service.ask(request.question());
    }
}
