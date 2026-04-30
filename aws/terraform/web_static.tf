resource "aws_s3_bucket" "web_static" {
  bucket = var.web_domain_name

  tags = merge(local.common_tags, {
    Name    = var.web_domain_name
    Purpose = "rss-ai-static-web"
  })
}

resource "aws_s3_bucket_ownership_controls" "web_static" {
  bucket = aws_s3_bucket.web_static.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "web_static" {
  bucket = aws_s3_bucket.web_static.id

  block_public_acls       = false
  block_public_policy     = false
  ignore_public_acls      = true
  restrict_public_buckets = false
}

resource "aws_s3_bucket_server_side_encryption_configuration" "web_static" {
  bucket = aws_s3_bucket.web_static.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_website_configuration" "web_static" {
  bucket = aws_s3_bucket.web_static.id

  index_document {
    suffix = "index.html"
  }

  error_document {
    key = "index.html"
  }
}

resource "aws_s3_bucket_policy" "web_static_public_read" {
  bucket = aws_s3_bucket.web_static.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowPublicReadForStaticWebsite"
        Effect    = "Allow"
        Principal = "*"
        Action    = "s3:GetObject"
        Resource  = "${aws_s3_bucket.web_static.arn}/*"
      }
    ]
  })

  depends_on = [
    aws_s3_bucket_public_access_block.web_static,
    aws_s3_bucket_ownership_controls.web_static,
  ]
}
