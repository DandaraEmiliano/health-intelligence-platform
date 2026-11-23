output "kms_key_arn" {
  value = aws_kms_key.data.arn
}

output "db_address" {
  value = aws_db_instance.this.address
}

output "db_master_secret_arn" {
  value = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "redis_address" {
  value = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "documents_bucket" {
  value = aws_s3_bucket.documents.bucket
}

output "documents_bucket_arn" {
  value = aws_s3_bucket.documents.arn
}

output "queue_arns" {
  value = { for k, q in aws_sqs_queue.this : k => q.arn }
}

output "queue_names" {
  value = { for k, q in aws_sqs_queue.this : k => q.name }
}

output "secret_arns" {
  value = merge({ for k, s in aws_secretsmanager_secret.app : k => s.arn }, { "redis-auth-token" = aws_secretsmanager_secret.redis.arn })
}
