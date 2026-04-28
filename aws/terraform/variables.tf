variable "app_name" {
  description = "Application/resource name prefix."
  type        = string
  default     = "rss-ai"
}

variable "aws_region" {
  description = "AWS region for the personal backend."
  type        = string
  default     = "us-east-1"
}

variable "api_token" {
  description = "Shared personal API token. The deploy script generates one when omitted."
  type        = string
  sensitive   = true
  default     = ""
}

variable "api_lambda_zip_path" {
  description = "Path to the packaged core API Lambda zip."
  type        = string
  default     = "../dist/rss-api.zip"
}

variable "browser_image_uri" {
  description = "Full ECR image URI for the browser-fetch Lambda."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}\\.dkr\\.ecr\\.[a-z0-9-]+\\.amazonaws\\.com/.+:.+$", var.browser_image_uri))
    error_message = "browser_image_uri must be a real tagged ECR image URI, for example 123456789012.dkr.ecr.us-east-1.amazonaws.com/rss-ai-browser-fetcher:20260428203000. Run aws/scripts/deploy_rss_api.sh so ECR is created, the image is pushed, and Terraform receives this value."
  }
}

variable "openai_api_key" {
  description = "Optional OpenAI-compatible API key for backend AI calls."
  type        = string
  sensitive   = true
  default     = ""
}

variable "minimax_api_key" {
  description = "Optional MiniMax API key alias for OpenAI-compatible AI calls."
  type        = string
  sensitive   = true
  default     = ""
}

variable "openai_api_base" {
  description = "OpenAI-compatible API base URL."
  type        = string
  default     = "https://api.openai.com/v1"
}

variable "ai_model" {
  description = "Default OpenAI-compatible model."
  type        = string
  default     = "gpt-5.4"
}

variable "embedding_model" {
  description = "Default OpenAI-compatible embeddings model for semantic search."
  type        = string
  default     = "text-embedding-3-small"
}

variable "codex_model" {
  description = "Default Codex subscription model."
  type        = string
  default     = "gpt-5.4"
}

variable "codex_client_version" {
  description = "Codex client version sent to the ChatGPT Codex backend."
  type        = string
  default     = "0.118.0"
}

variable "codex_auth_s3_key" {
  description = "S3 key containing ~/.codex/auth.json."
  type        = string
  default     = "codex/auth.json"
}

variable "enable_scheduled_refresh" {
  description = "Whether EventBridge should periodically refresh feeds."
  type        = bool
  default     = false
}

variable "scheduled_refresh_expression" {
  description = "EventBridge schedule expression for refreshes."
  type        = string
  default     = "rate(6 hours)"
}

variable "log_retention_days" {
  description = "CloudWatch log retention for Lambda logs."
  type        = number
  default     = 14
}
