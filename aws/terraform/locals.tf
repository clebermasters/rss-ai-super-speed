resource "random_password" "api_token" {
  length  = 32
  special = false
}

locals {
  name_prefix         = var.app_name
  effective_api_token = var.api_token != "" ? var.api_token : random_password.api_token.result
  private_bucket      = "${replace(var.app_name, "_", "-")}-private-${data.aws_caller_identity.current.account_id}-${var.aws_region}"
  common_tags = {
    Application = var.app_name
    ManagedBy   = "terraform"
    Owner       = "personal"
  }
}
