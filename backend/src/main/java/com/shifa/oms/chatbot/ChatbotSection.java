package com.shifa.oms.chatbot;

import com.shifa.oms.chatbot.dto.ChatbotAnswer.Table;

import java.util.List;

/** One part of an answer: some lines of text and, optionally, a table. Parts are combined into one reply. */
record ChatbotSection(List<String> lines, Table table) {

    static ChatbotSection ofLines(List<String> lines) {
        return new ChatbotSection(lines, null);
    }
}
