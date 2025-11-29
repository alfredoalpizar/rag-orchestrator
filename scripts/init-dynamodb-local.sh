#!/bin/bash
set -e

# DynamoDB Local doesn't require real AWS credentials, but AWS CLI does
# Set dummy credentials for local testing
export AWS_ACCESS_KEY_ID=dummy
export AWS_SECRET_ACCESS_KEY=dummy

echo "Initializing DynamoDB Local table: rag-document-locks"

aws dynamodb create-table \
  --table-name rag-document-locks \
  --attribute-definitions AttributeName=doc_id,AttributeType=S \
  --key-schema AttributeName=doc_id,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --endpoint-url http://localhost:8000 \
  --region us-east-1 \
  --no-cli-pager

echo "✅ Table created successfully"
echo ""
echo "Enabling TTL for automatic lock expiration..."

aws dynamodb update-time-to-live \
  --table-name rag-document-locks \
  --time-to-live-specification "Enabled=true, AttributeName=expires_at" \
  --endpoint-url http://localhost:8000 \
  --region us-east-1 \
  --no-cli-pager

echo "✅ TTL enabled on 'expires_at' attribute"
echo ""
echo "To verify:"
echo "  aws dynamodb list-tables --endpoint-url http://localhost:8000 --region us-east-1"
echo "  aws dynamodb describe-table --table-name rag-document-locks --endpoint-url http://localhost:8000 --region us-east-1"
echo ""
echo "To delete (if needed):"
echo "  aws dynamodb delete-table --table-name rag-document-locks --endpoint-url http://localhost:8000 --region us-east-1"
