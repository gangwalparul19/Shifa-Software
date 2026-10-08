-- Staff profile photo (separate from the govt-ID proof document).
--
-- A salesperson now has TWO uploaded images: a profile photo and a government
-- ID image. Only the opaque storage key is persisted here; the bytes live in
-- the pluggable StorageService (LOCAL / DB / S3), and are compressed before
-- storage to keep object size (and S3 cost) down.
ALTER TABLE users
    ADD COLUMN profile_image_key VARCHAR(255) NULL;
