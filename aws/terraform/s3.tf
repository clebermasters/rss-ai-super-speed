resource "aws_s3_bucket" "private" {
  bucket = local.private_bucket
  tags   = local.common_tags
}

resource "aws_s3_bucket_public_access_block" "private" {
  bucket                  = aws_s3_bucket.private.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "private" {
  bucket = aws_s3_bucket.private.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "private" {
  bucket = aws_s3_bucket.private.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "private_cache" {
  bucket = aws_s3_bucket.private.id

  rule {
    id     = "expire-tts-cache"
    status = "Enabled"

    filter {
      prefix = "tts-cache/"
    }

    expiration {
      days = var.s3_tts_cache_expiration_days
    }

    noncurrent_version_expiration {
      noncurrent_days = var.s3_cache_noncurrent_expiration_days
    }
  }

  rule {
    id     = "expire-browser-results"
    status = "Enabled"

    filter {
      prefix = "browser-results/"
    }

    expiration {
      days = var.s3_browser_results_expiration_days
    }

    noncurrent_version_expiration {
      noncurrent_days = var.s3_cache_noncurrent_expiration_days
    }
  }

  rule {
    id     = "delete-incomplete-cache-uploads"
    status = "Enabled"

    filter {
      prefix = ""
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }

    expiration {
      expired_object_delete_marker = true
    }
  }

  depends_on = [aws_s3_bucket_versioning.private]
}
