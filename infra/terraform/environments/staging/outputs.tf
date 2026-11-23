output "load_balancer_dns_name" {
  value = module.services.load_balancer_dns_name
}

output "ecr_repository_urls" {
  value = module.services.ecr_repository_urls
}

output "cluster_name" {
  value = module.services.cluster_name
}
