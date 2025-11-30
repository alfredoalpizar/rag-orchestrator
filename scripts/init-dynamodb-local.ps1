# PowerShell script to initialize DynamoDB Local table
# Works on Windows without requiring AWS CLI

Write-Host "Initializing DynamoDB Local table: rag-document-locks" -ForegroundColor Cyan

# Set dummy AWS credentials (required by DynamoDB Local)
$env:AWS_ACCESS_KEY_ID = "dummy"
$env:AWS_SECRET_ACCESS_KEY = "dummy"

# Create table using AWS SDK via Docker
$createTableCommand = @"
docker run --rm --network host amazon/aws-cli dynamodb create-table \
  --table-name rag-document-locks \
  --attribute-definitions AttributeName=doc_id,AttributeType=S \
  --key-schema AttributeName=doc_id,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --endpoint-url http://localhost:8000 \
  --region us-east-1 \
  --no-cli-pager
"@

Write-Host "Creating table..." -ForegroundColor Yellow
docker run --rm --network host `
  -e AWS_ACCESS_KEY_ID=dummy `
  -e AWS_SECRET_ACCESS_KEY=dummy `
  amazon/aws-cli dynamodb create-table `
  --table-name rag-document-locks `
  --attribute-definitions AttributeName=doc_id,AttributeType=S `
  --key-schema AttributeName=doc_id,KeyType=HASH `
  --billing-mode PAY_PER_REQUEST `
  --endpoint-url http://host.docker.internal:8000 `
  --region us-east-1 `
  --no-cli-pager

if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ Table created successfully" -ForegroundColor Green

    Write-Host "`nEnabling TTL for automatic lock expiration..." -ForegroundColor Yellow
    docker run --rm --network host `
      -e AWS_ACCESS_KEY_ID=dummy `
      -e AWS_SECRET_ACCESS_KEY=dummy `
      amazon/aws-cli dynamodb update-time-to-live `
      --table-name rag-document-locks `
      --time-to-live-specification "Enabled=true, AttributeName=expires_at" `
      --endpoint-url http://host.docker.internal:8000 `
      --region us-east-1 `
      --no-cli-pager

    Write-Host "✅ TTL enabled on 'expires_at' attribute" -ForegroundColor Green
} else {
    Write-Host "❌ Failed to create table" -ForegroundColor Red
    exit 1
}

Write-Host "`nTo verify:" -ForegroundColor Cyan
Write-Host "  docker run --rm -e AWS_ACCESS_KEY_ID=dummy -e AWS_SECRET_ACCESS_KEY=dummy amazon/aws-cli dynamodb list-tables --endpoint-url http://host.docker.internal:8000 --region us-east-1"
