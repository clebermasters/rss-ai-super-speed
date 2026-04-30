resource "aws_cloudwatch_log_group" "api" {
  name              = "/aws/lambda/${local.name_prefix}-api"
  retention_in_days = var.log_retention_days
  tags              = local.common_tags
}

resource "aws_lambda_function" "api" {
  function_name    = "${local.name_prefix}-api"
  role             = aws_iam_role.api_lambda.arn
  runtime          = "python3.12"
  handler          = "app.handler"
  filename         = var.api_lambda_zip_path
  source_code_hash = filebase64sha256(var.api_lambda_zip_path)
  architectures    = ["arm64"]
  timeout          = 120
  memory_size      = 512

  environment {
    variables = {
      TABLE_NAME                  = aws_dynamodb_table.rss.name
      APP_BUCKET                  = aws_s3_bucket.private.bucket
      RSS_AI_API_TOKEN            = local.effective_api_token
      BROWSER_FETCHER_FUNCTION    = aws_lambda_function.browser_fetcher.function_name
      CODEX_AUTH_S3_KEY           = var.codex_auth_s3_key
      OPENAI_API_KEY              = var.openai_api_key
      MINIMAX_API_KEY             = var.minimax_api_key
      OPENAI_API_BASE             = var.openai_api_base
      AI_MODEL                    = var.ai_model
      EMBEDDING_MODEL             = var.embedding_model
      OPENAI_TTS_API_BASE         = var.openai_tts_api_base
      OPENAI_TTS_MODEL            = var.openai_tts_model
      OPENAI_TTS_VOICE            = var.openai_tts_voice
      OPENAI_CODEX_MODEL          = var.codex_model
      OPENAI_CODEX_CLIENT_VERSION = var.codex_client_version
      BROWSER_BYPASS_ENABLED      = "true"
      BROWSER_RESULT_INLINE_MAX   = "180000"
      DEFAULT_REFRESH_LIMIT       = "20"
    }
  }

  depends_on = [
    aws_cloudwatch_log_group.api,
    aws_iam_role_policy_attachment.api_basic,
    aws_iam_role_policy_attachment.api_access
  ]

  tags = local.common_tags
}
