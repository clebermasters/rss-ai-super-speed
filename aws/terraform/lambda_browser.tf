resource "aws_cloudwatch_log_group" "browser" {
  name              = "/aws/lambda/${local.name_prefix}-browser-fetcher"
  retention_in_days = var.log_retention_days
  tags              = local.common_tags
}

resource "aws_lambda_function" "browser_fetcher" {
  function_name                  = "${local.name_prefix}-browser-fetcher"
  role                           = aws_iam_role.browser_lambda.arn
  package_type                   = "Image"
  image_uri                      = var.browser_image_uri
  architectures                  = ["x86_64"]
  timeout                        = 120
  memory_size                    = 2048
  reserved_concurrent_executions = 1

  environment {
    variables = {
      APP_BUCKET                = aws_s3_bucket.private.bucket
      BROWSER_RESULT_INLINE_MAX = "180000"
      PLAYWRIGHT_BROWSERS_PATH  = "/ms-playwright"
      RSS_AI_LOG_LEVEL          = "INFO"
    }
  }

  depends_on = [
    aws_cloudwatch_log_group.browser,
    aws_iam_role_policy_attachment.browser_basic,
    aws_iam_role_policy_attachment.browser_access
  ]

  tags = local.common_tags
}
