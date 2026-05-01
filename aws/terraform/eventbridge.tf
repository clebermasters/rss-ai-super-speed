resource "aws_cloudwatch_event_rule" "scheduled_refresh" {
  count               = var.enable_scheduled_refresh ? 1 : 0
  name                = "${local.name_prefix}-scheduled-refresh"
  schedule_expression = var.scheduled_refresh_expression
  tags                = local.common_tags
}

resource "aws_cloudwatch_event_target" "scheduled_refresh" {
  count = var.enable_scheduled_refresh ? 1 : 0
  rule  = aws_cloudwatch_event_rule.scheduled_refresh[0].name
  arn   = aws_lambda_function.api.arn
  input = jsonencode({
    source = "rss-ai.scheduler"
    action = "tag-ai-prefetch"
  })
}

resource "aws_lambda_permission" "scheduled_refresh" {
  count         = var.enable_scheduled_refresh ? 1 : 0
  statement_id  = "AllowExecutionFromEventBridge"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.api.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.scheduled_refresh[0].arn
}
