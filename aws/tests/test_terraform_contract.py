from __future__ import annotations

import unittest
from pathlib import Path


class TerraformContractTest(unittest.TestCase):
    def test_backend_uses_partial_s3_state_config(self) -> None:
        backend = Path("aws/terraform/backend.tf").read_text(encoding="utf-8")
        deploy = Path("aws/scripts/deploy_rss_api.sh").read_text(encoding="utf-8")
        self.assertIn('backend "s3" {}', backend)
        self.assertNotIn("bucket  =", backend)
        self.assertIn('TERRAFORM_STATE_BUCKET', deploy)
        self.assertIn('-backend-config="bucket=$STATE_BUCKET"', deploy)
        self.assertIn('-backend-config="encrypt=true"', deploy)

    def test_required_outputs_exist(self) -> None:
        outputs = Path("aws/terraform/outputs.tf").read_text(encoding="utf-8")
        for name in (
            "api_base_url",
            "api_token",
            "dynamodb_table_name",
            "private_bucket_name",
            "browser_lambda_name",
            "browser_ecr_repository_url",
        ):
            self.assertIn(f'output "{name}"', outputs)


if __name__ == "__main__":
    unittest.main()
