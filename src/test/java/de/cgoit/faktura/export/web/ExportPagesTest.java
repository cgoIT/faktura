package de.cgoit.faktura.export.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import de.cgoit.faktura.SqliteTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@SqliteTest
class ExportPagesTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void pageOffersTheYearAndBothDownloads() throws Exception {
        mvc.perform(get("/export"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/export/invoices.csv?year=")))
                .andExpect(content().string(containsString("/export/payments.csv?year=")));
    }

    @Test
    void invoiceDownloadIsUtf8CsvWithBomAndFilename() throws Exception {
        var result = mvc.perform(get("/export/invoices.csv").param("year", "2027"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Type", containsString("UTF-8")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("invoices-2027.csv")))
                .andReturn();

        var body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);

        assertThat(body).startsWith("﻿Nummer;");
    }

    @Test
    void paymentDownloadHasItsOwnFilename() throws Exception {
        mvc.perform(get("/export/payments.csv").param("year", "2027"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("payments-2027.csv")));
    }

    @Test
    void implausibleYearIsRejected() throws Exception {
        mvc.perform(get("/export/invoices.csv").param("year", "1800"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/export/payments.csv").param("year", "9999"))
                .andExpect(status().isBadRequest());
    }
}
