output "api_base_url" {
  value = aws_apigatewayv2_api.http.api_endpoint
}

output "api_token" {
  value     = local.effective_api_token
  sensitive = true
}

output "dynamodb_table_name" {
  value = aws_dynamodb_table.rss.name
}

output "private_bucket_name" {
  value = aws_s3_bucket.private.bucket
}

output "browser_lambda_name" {
  value = aws_lambda_function.browser_fetcher.function_name
}

output "browser_ecr_repository_url" {
  value = aws_ecr_repository.browser_fetcher.repository_url
}

output "codex_auth_s3_key" {
  value = var.codex_auth_s3_key
}
