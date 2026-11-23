output "load_balancer_dns_name" {
  description = "Point domain_name (CNAME/alias) here."
  value       = aws_lb.this.dns_name
}

output "ecr_repository_urls" {
  value = { for k, r in aws_ecr_repository.this : k => r.repository_url }
}

output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "security_group_ids" {
  value = {
    core_api   = aws_security_group.core_api.id
    bff        = aws_security_group.bff.id
    ai_service = aws_security_group.ai_service.id
  }
}
