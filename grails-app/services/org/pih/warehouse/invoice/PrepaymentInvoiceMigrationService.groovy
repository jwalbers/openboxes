package org.pih.warehouse.invoice

import grails.gorm.transactions.Transactional

import org.pih.warehouse.order.OrderAdjustment
import org.pih.warehouse.order.OrderItem

/**
 * This service exists for the sole purpose of performing data migrations for changelog 0.9.x/2024-08-20-0000.
 * The logic was only put in a service class so that it could be unit tested. Do not use it in regular flows.
 */
@Transactional
class PrepaymentInvoiceMigrationService {

    /**
     * For STEP 1 of the migration.
     *
     * Set the amount field for all prepayment invoice items that existed before the partial invoicing
     * feature was introduced.
     *
     * We don't need to check for canceled items or adjustment during this step because anything that was canceled
     * before the prepayment invoice was created wouldn't be included in the prepayment invoice in the first place.
     */
    void updateAmountForPrepaymentInvoiceItems() {
        InvoiceType prepaymentInvoiceType = InvoiceType.findByCode(InvoiceTypeCode.PREPAYMENT_INVOICE)
        List<Invoice> prepaymentInvoices = Invoice.findAllByInvoiceType(prepaymentInvoiceType)
        for (Invoice prepaymentInvoice : prepaymentInvoices) {
            for (InvoiceItem prepaymentInvoiceItem : prepaymentInvoice.invoiceItems) {
                // The amount field will only be null for pre-existing, non-inversed invoices since the field was
                // not being used before the partial invoicing feature was introduced.
                if (prepaymentInvoiceItem.amount != null) {
                    continue
                }

                prepaymentInvoiceItem.amount = computePrepaymentInvoiceItemAmount(prepaymentInvoiceItem)
                prepaymentInvoiceItem.save(failOnError: true)
            }
        }
    }

    private BigDecimal computePrepaymentInvoiceItemAmount(InvoiceItem prepaymentInvoiceItem) {
        BigDecimal prepaymentPercent = prepaymentInvoiceItem.order.prepaymentPercent

        // If the invoice item is for an adjustment.
        OrderAdjustment orderAdjustment = prepaymentInvoiceItem.orderAdjustment
        if (orderAdjustment) {
            return orderAdjustment.totalAdjustments * prepaymentPercent
        }

        // Else the invoice item is for an order item.
        OrderItem orderItem = prepaymentInvoiceItem.orderItem
        return (orderItem.quantity ?: 0) * (orderItem.unitPrice ?: 0.0) * prepaymentPercent
    }

    /**
     * For STEP 2 of the migration.
     *
     * For every item in a given prepayment invoice, computes the inverse invoice item and insert it to the database
     * by adding it to the given final invoice.
     *
     * This method assumes that because there is both a prepayment invoice and a final invoice, that the order
     * associated with both invoices has been fully invoiced, and so there's no need to check each item of the
     * prepayment invoice individually, they should ALL need to have inverse items created for them.
     *
     * Additionally, we don't need to check for canceled items or adjustments since the inverse is based entirely
     * off the prepayment line. Even if the items are canceled, we still need to inverse the full prepaid amount.
     */
    Invoice generateInverseInvoiceItems(Invoice prepaymentInvoice, Invoice finalInvoice) {
        for (InvoiceItem prepaymentInvoiceItem : prepaymentInvoice.invoiceItems) {
            InvoiceItem inverseItem = createInverseInvoiceItem(prepaymentInvoiceItem)
            finalInvoice.addToInvoiceItems(inverseItem)
        }
        return finalInvoice.save(failOnError: true)
    }

    private InvoiceItem createInverseInvoiceItem(InvoiceItem prepaymentInvoiceItem) {
        // Because we already computed the amount field for prepayment invoice items in step 1, and because there will
        // always be a one-to-one mapping of prepayment invoice item to final invoice item for pre-existing data,
        // all we need to do to create the inverse item is copy the prepayment invoice item and inverse the amount.
        InvoiceItem inverseItem = prepaymentInvoiceItem.clone()

        inverseItem.inverse = true
        inverseItem.amount = prepaymentInvoiceItem.amount * -1

        return inverseItem
    }
}
