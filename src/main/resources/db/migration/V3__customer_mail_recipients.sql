-- Addresses a document of the customer is sent to by mail (comma separated); empty: the contact person or the invoice
-- e-mail address. The invoice e-mail address stays the single address of the electronic invoice.
ALTER TABLE customer ADD COLUMN mail_recipients VARCHAR(500);
