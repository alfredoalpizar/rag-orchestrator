#!/usr/bin/env python3
"""
Simple Python script to initialize DynamoDB Local table.
No AWS CLI required - uses boto3 directly.
"""
import sys

try:
    import boto3
    from botocore.exceptions import ClientError
except ImportError:
    print("❌ boto3 not installed. Installing...")
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "boto3"])
    import boto3
    from botocore.exceptions import ClientError

# Configure DynamoDB Local client
dynamodb = boto3.client(
    'dynamodb',
    endpoint_url='http://localhost:8000',
    region_name='us-east-1',
    aws_access_key_id='dummy',
    aws_secret_access_key='dummy'
)

print("🔄 Creating DynamoDB table: rag-document-locks")

try:
    # Create table
    response = dynamodb.create_table(
        TableName='rag-document-locks',
        KeySchema=[
            {'AttributeName': 'doc_id', 'KeyType': 'HASH'}
        ],
        AttributeDefinitions=[
            {'AttributeName': 'doc_id', 'AttributeType': 'S'}
        ],
        BillingMode='PAY_PER_REQUEST'
    )

    print("✅ Table created successfully!")
    print(f"   Table ARN: {response['TableDescription']['TableArn']}")
    print(f"   Status: {response['TableDescription']['TableStatus']}")

    # Enable TTL
    print("\n🔄 Enabling TTL on 'expires_at' attribute...")
    dynamodb.update_time_to_live(
        TableName='rag-document-locks',
        TimeToLiveSpecification={
            'Enabled': True,
            'AttributeName': 'expires_at'
        }
    )
    print("✅ TTL enabled successfully!")

    print("\n✨ DynamoDB Local is ready!")
    print("\nTo verify:")
    print("  python -c \"import boto3; print(boto3.client('dynamodb', endpoint_url='http://localhost:8000', region_name='us-east-1', aws_access_key_id='dummy', aws_secret_access_key='dummy').list_tables())\"")

except ClientError as e:
    if e.response['Error']['Code'] == 'ResourceInUseException':
        print("⚠️  Table already exists!")
        print("To delete and recreate:")
        print("  python -c \"import boto3; boto3.client('dynamodb', endpoint_url='http://localhost:8000', region_name='us-east-1', aws_access_key_id='dummy', aws_secret_access_key='dummy').delete_table(TableName='rag-document-locks')\"")
    else:
        print(f"❌ Error: {e}")
        sys.exit(1)
except Exception as e:
    print(f"❌ Unexpected error: {e}")
    sys.exit(1)
