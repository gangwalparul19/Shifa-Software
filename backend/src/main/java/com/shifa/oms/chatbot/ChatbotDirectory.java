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

    /** "No receiving account recorded" is the Settlement Center label for NULL bank_account_id payment rows. */
    static final String UNASSIGNED_ACCOUNT = "No receiving account recorded";

    String accountName(Long accountId) {
        if (accountId == null) {
            return UNASSIGNED_ACCOUNT;
        }
        return accountNames.getOrDefault(accountId, "Account #" + accountId);
    }
}
