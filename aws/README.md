# RSS AI Backend Deployment

Use the guarded backend deployment entrypoint:

```bash
aws/scripts/deploy_backend.sh
```

Set Terraform state configuration in your shell or ignored `.env` file first:

```bash
TERRAFORM_STATE_BUCKET=your-private-terraform-state-bucket
TERRAFORM_STATE_KEY=rss-ai/terraform.tfstate
TERRAFORM_STATE_REGION=us-east-1
```

The script checks local tools, AWS credentials, Terraform S3 backend access, Docker availability, repository layout, Codex auth shape, Terraform formatting/validation, and backend unit tests before deployment. After deployment it runs deployed API smoke tests, invokes the browser-fetch Lambda, and checks Terraform for drift.

Useful modes:

```bash
aws/scripts/deploy_backend.sh --preflight-only
aws/scripts/deploy_backend.sh --require-codex-auth
aws/scripts/deploy_backend.sh --skip-tests
aws/scripts/deploy_backend.sh --skip-smoke
```

The lower-level deploy script remains available at `aws/scripts/deploy_rss_api.sh`, but day-to-day backend deployments should use `deploy_backend.sh` so preconditions are checked first.

After deployment, the script writes `aws/generated/rss-api.env`. The Android Docker build reads this generated file automatically, so a freshly built APK starts with the deployed API URL and token already configured:

```bash
./build-android.sh debug
```

The app still allows changing the API URL and token in Settings. Shell environment variables override generated values when you need to build against another backend temporarily.
