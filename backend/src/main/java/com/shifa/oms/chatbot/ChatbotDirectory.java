package com.shifa.oms.chatbot;

import com.shifa.oms.chatbot.domain.AccountRef;
import com.shifa.oms.chatbot.domain.PersonRef;
import com.shifa.oms.chatbot.domain.ProductRef;

import java.util.List;
import java.util.Map;

/**
 * The names the chatbot can resolve, loaded fresh for each question: staff,
 * catalogue products and bank / QR accounts, plus id-to-name maps for wording
 * answers.
 */
record ChatbotDirectory(List<PersonRef> people, List<ProductRef> catalogue, List<AccountRef> accounts,
                        Map<Long, String> staffNames, Map<Long, String> accountNames) {

    /** "Unassigned / legacy" is what the Settlement Center calls payments with no recorded account. */
    static final String UNASSIGNED_ACCOUNT = "Unassigned / legacy";

    String accountName(Long accountId) {
        if (accountId == null) {
            return UNASSIGNED_ACCOUNT;
        }
        return accountNames.getOrDefault(accountId, "Account #" + accountId);
    }
}
