package com.shifa.oms.integration.meta;

import com.shifa.oms.integration.MalformedPayloadException;
import com.shifa.oms.integration.meta.dto.MetaLeadEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MetaLeadNotificationCodec} (spec {@code meta-lead-sync}, Req 3.1, 3.4).
 */
class MetaLeadNotificationCodecTest {

    @Test
    void parsesLeadgenEntries() {
        String json = """
                {
                  "object": "page",
                  "entry": [{
                    "id": "105357624712950",
                    "time": 1700000000,
                    "changes": [{
                      "field": "leadgen",
                      "value": {
                        "leadgen_id": "L-1",
                        "form_id": "F-9",
                        "page_id": "105357624712950",
                        "created_time": 1700000123
                      }
                    }]
                  }]
                }
                """;
        List<MetaLeadEntry> entries = MetaLeadNotificationCodec.parse(json);
        assertThat(entries).hasSize(1);
        MetaLeadEntry entry = entries.get(0);
        assertThat(entry.leadgenId()).isEqualTo("L-1");
        assertThat(entry.formId()).isEqualTo("F-9");
        assertThat(entry.pageId()).isEqualTo("105357624712950");
        assertThat(entry.createdTime()).isEqualTo(1700000123L);
    }

    @Test
    void ignoresNonLeadgenChanges() {
        String json = """
                {
                  "entry": [{
                    "changes": [
                      {"field": "feed", "value": {"item": "status"}},
                      {"field": "leadgen", "value": {"leadgen_id": "L-2"}}
                    ]
                  }]
                }
                """;
        List<MetaLeadEntry> entries = MetaLeadNotificationCodec.parse(json);
        assertThat(entries).extracting(MetaLeadEntry::leadgenId).containsExactly("L-2");
    }

    @Test
    void skipsLeadgenChangeMissingLeadId() {
        String json = """
                {"entry": [{"changes": [{"field": "leadgen", "value": {"form_id": "F-1"}}]}]}
                """;
        assertThat(MetaLeadNotificationCodec.parse(json)).isEmpty();
    }

    @Test
    void returnsEmptyWhenNoEntries() {
        assertThat(MetaLeadNotificationCodec.parse("{\"object\":\"page\"}")).isEmpty();
    }

    @Test
    void throwsOnMalformedJson() {
        assertThatThrownBy(() -> MetaLeadNotificationCodec.parse("not-json"))
                .isInstanceOf(MalformedPayloadException.class);
        assertThatThrownBy(() -> MetaLeadNotificationCodec.parse(""))
                .isInstanceOf(MalformedPayloadException.class);
    }
}
