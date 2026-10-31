-- How a customer is addressed in mails (FORMAL | INFORMAL, empty: the default template) and which tone a mail template has.
ALTER TABLE customer ADD COLUMN mail_tone VARCHAR(10);
ALTER TABLE mail_template ADD COLUMN tone VARCHAR(10);

UPDATE mail_template SET tone = 'FORMAL' WHERE name = 'Formell' AND scope <> 'NOTIFICATION_DIGEST';
UPDATE mail_template SET tone = 'INFORMAL' WHERE name = 'Informell' AND scope <> 'NOTIFICATION_DIGEST';
