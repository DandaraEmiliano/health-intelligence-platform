# ECS on Fargate behind one HTTPS load balancer. The web app and the API share one origin:
# /api/* goes to core-api, everything else to the web container. ai-service has no public route.

data "aws_region" "current" {}

locals {
  services = toset(["core-api", "ai-service", "web"])
  region   = data.aws_region.current.region
  origin   = "https://${var.domain_name}"
}

# --- images ------------------------------------------------------------------------------------

resource "aws_ecr_repository" "this" {
  for_each             = local.services
  name                 = "${var.name}/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = var.kms_key_arn
  }
}

resource "aws_ecr_lifecycle_policy" "this" {
  for_each   = aws_ecr_repository.this
  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 30 images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 30 }
      action       = { type = "expire" }
    }]
  })
}

# --- cluster, logs, service discovery --------------------------------------------------------

resource "aws_ecs_cluster" "this" {
  name = var.name
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_cloudwatch_log_group" "this" {
  for_each          = local.services
  name              = "/${var.name}/${each.key}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn
}

resource "aws_service_discovery_private_dns_namespace" "this" {
  name = "${var.name}.internal"
  vpc  = var.vpc_id
}

resource "aws_service_discovery_service" "ai" {
  name = "ai-service"
  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.this.id
    routing_policy = "MULTIVALUE"
    dns_records {
      type = "A"
      ttl  = 10
    }
  }
}

# --- load balancer -----------------------------------------------------------------------------

# Accepted: the single public entry point (HTTPS only, invalid headers dropped).
#trivy:ignore:AVD-AWS-0053
resource "aws_lb" "this" {
  name                       = var.name
  load_balancer_type         = "application"
  internal                   = false
  subnets                    = var.public_subnet_ids
  security_groups            = [aws_security_group.alb.id]
  drop_invalid_header_fields = true
  enable_deletion_protection = true
}

resource "aws_lb_target_group" "web" {
  name        = "${var.name}-web"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = var.vpc_id
  health_check {
    path    = "/"
    matcher = "200"
  }
}

resource "aws_lb_target_group" "core" {
  name        = "${var.name}-core"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = var.vpc_id
  health_check {
    path    = "/actuator/health/readiness"
    matcher = "200"
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.web.arn
  }
}

resource "aws_lb_listener_rule" "api" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 10
  condition {
    path_pattern {
      values = ["/api/*"]
    }
  }
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.core.arn
  }
}

# Metrics, API docs and the rest of actuator are never public (scraped inside the VPC).
resource "aws_lb_listener_rule" "internal_only" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 5
  condition {
    path_pattern {
      values = ["/actuator/*", "/v3/api-docs*", "/swagger-ui*"]
    }
  }
  action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Not found"
      status_code  = "404"
    }
  }
}

# --- IAM: one execution role, one task role per service with only what it uses ----------------

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${var.name}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets" {
  name = "read-app-secrets"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = "secretsmanager:GetSecretValue", Resource = values(var.secret_arns) },
      { Effect = "Allow", Action = "kms:Decrypt", Resource = var.kms_key_arn }
    ]
  })
}

resource "aws_iam_role" "core_api" {
  name               = "${var.name}-core-api"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy" "core_api" {
  name = "documents-and-queues"
  role = aws_iam_role.core_api.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      # Upload, pre-signed download and purge of documents/<uuid> only.
      { Effect = "Allow", Action = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"], Resource = "${var.documents_bucket_arn}/documents/*" },
      { Effect = "Allow", Action = ["sqs:SendMessage", "sqs:GetQueueUrl"], Resource = var.queue_arns["document-extraction-requests"] },
      { Effect = "Allow", Action = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueUrl", "sqs:ChangeMessageVisibility"], Resource = var.queue_arns["document-extraction-results"] },
      { Effect = "Allow", Action = ["kms:Decrypt", "kms:GenerateDataKey"], Resource = var.kms_key_arn }
    ]
  })
}

resource "aws_iam_role" "ai_service" {
  name               = "${var.name}-ai-service"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy" "ai_service" {
  name = "read-documents-and-queues"
  role = aws_iam_role.ai_service.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      # Reads the PDF to extract; never writes or deletes documents.
      { Effect = "Allow", Action = ["s3:GetObject"], Resource = "${var.documents_bucket_arn}/documents/*" },
      { Effect = "Allow", Action = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueUrl", "sqs:ChangeMessageVisibility"], Resource = var.queue_arns["document-extraction-requests"] },
      { Effect = "Allow", Action = ["sqs:SendMessage", "sqs:GetQueueUrl"], Resource = var.queue_arns["document-extraction-results"] },
      { Effect = "Allow", Action = ["kms:Decrypt", "kms:GenerateDataKey"], Resource = var.kms_key_arn }
    ]
  })
}

resource "aws_iam_role" "web" {
  name               = "${var.name}-web"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

# --- task definitions -------------------------------------------------------------------------

locals {
  common_container = {
    essential              = true
    readonlyRootFilesystem = true
    user                   = "10001:10001"
    linuxParameters        = { capabilities = { drop = ["ALL"] }, initProcessEnabled = true }
  }

  log = { for s in local.services : s => {
    logDriver = "awslogs"
    options = {
      awslogs-group         = aws_cloudwatch_log_group.this[s].name
      awslogs-region        = local.region
      awslogs-stream-prefix = s
    }
  } }

  secret = { for k, arn in var.secret_arns : k => arn }
}

resource "aws_ecs_task_definition" "core_api" {
  family                   = "${var.name}-core-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 1024
  memory                   = 2048
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.core_api.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }
  volume {
    name = "tmp"
  }
  container_definitions = jsonencode([merge(local.common_container, {
    name         = "core-api"
    image        = "${aws_ecr_repository.this["core-api"].repository_url}:${var.image_tag}"
    portMappings = [{ containerPort = 8080, protocol = "tcp" }]
    mountPoints  = [{ sourceVolume = "tmp", containerPath = "/tmp" }]
    environment = [
      { name = "CORE_DB_URL", value = "jdbc:postgresql://${var.db_address}:5432/hip?sslmode=require" },
      { name = "REDIS_HOST", value = var.redis_address },
      { name = "SPRING_DATA_REDIS_SSL_ENABLED", value = "true" },
      { name = "OIDC_ISSUER_URI", value = var.oidc_issuer_url },
      { name = "KEYCLOAK_BASE_URL", value = var.identity_admin_base_url },
      { name = "AWS_REGION", value = local.region },
      { name = "AWS_ENDPOINT_URL_S3", value = "" },
      { name = "AWS_ENDPOINT_URL_SQS", value = "" },
      { name = "DOCUMENTS_BUCKET", value = var.documents_bucket },
      { name = "HIP_EXTRACTION_QUEUE", value = var.queue_names["document-extraction-requests"] },
      { name = "HIP_EXTRACTION_RESULTS_QUEUE", value = var.queue_names["document-extraction-results"] },
      { name = "AI_SERVICE_URL", value = "http://ai-service.${var.name}.internal:8081" },
      { name = "CORS_ALLOWED_ORIGINS", value = local.origin },
      { name = "API_DOCS_ENABLED", value = "false" },
      { name = "TRACING_SAMPLING_PROBABILITY", value = "0.1" },
    ]
    secrets = [
      { name = "CORE_APP_PASSWORD", valueFrom = local.secret["db-core-app"] },
      { name = "CORE_OWNER_PASSWORD", valueFrom = local.secret["db-core-owner"] },
      { name = "SPRING_DATA_REDIS_PASSWORD", valueFrom = local.secret["redis-auth-token"] },
      { name = "KC_CORE_API_SERVICE_SECRET", valueFrom = local.secret["oidc-core-api-service"] },
      { name = "KC_CORE_API_ADMIN_SECRET", valueFrom = local.secret["oidc-core-api-admin"] },
    ]
    logConfiguration = local.log["core-api"]
  })])
}

resource "aws_ecs_task_definition" "ai_service" {
  family                   = "${var.name}-ai-service"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 1024
  memory                   = 2048
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.ai_service.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }
  volume {
    name = "tmp"
  }
  container_definitions = jsonencode([merge(local.common_container, {
    name         = "ai-service"
    image        = "${aws_ecr_repository.this["ai-service"].repository_url}:${var.image_tag}"
    portMappings = [{ containerPort = 8081, protocol = "tcp" }]
    mountPoints  = [{ sourceVolume = "tmp", containerPath = "/tmp" }]
    environment = [
      { name = "AI_DB_HOST", value = var.db_address },
      { name = "AI_OIDC_ISSUER", value = var.oidc_issuer_url },
      { name = "AWS_REGION", value = local.region },
      { name = "AI_EXTRACTION_REQUESTS_QUEUE", value = var.queue_names["document-extraction-requests"] },
      { name = "AI_EXTRACTION_RESULTS_QUEUE", value = var.queue_names["document-extraction-results"] },
    ]
    secrets = [
      { name = "AI_APP_PASSWORD", valueFrom = local.secret["db-ai-app"] },
      { name = "AI_OWNER_PASSWORD", valueFrom = local.secret["db-ai-owner"] },
      { name = "ANTHROPIC_API_KEY", valueFrom = local.secret["anthropic-api-key"] },
    ]
    logConfiguration = local.log["ai-service"]
  })])
}

resource "aws_ecs_task_definition" "web" {
  family                   = "${var.name}-web"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.web.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }
  dynamic "volume" {
    for_each = toset(["tmp", "cache", "conf"])
    content {
      name = volume.value
    }
  }
  container_definitions = jsonencode([merge(local.common_container, {
    name         = "web"
    user         = "101:101"
    image        = "${aws_ecr_repository.this["web"].repository_url}:${var.image_tag}"
    portMappings = [{ containerPort = 8080, protocol = "tcp" }]
    mountPoints = [
      { sourceVolume = "tmp", containerPath = "/tmp" },
      { sourceVolume = "cache", containerPath = "/var/cache/nginx" },
      { sourceVolume = "conf", containerPath = "/etc/nginx/conf.d" },
    ]
    environment = [
      { name = "API_ORIGIN", value = local.origin },
      { name = "OIDC_ORIGIN", value = regex("^https://[^/]+", var.oidc_issuer_url) },
    ]
    logConfiguration = local.log["web"]
  })])
}

# --- services --------------------------------------------------------------------------------

locals {
  task_definitions = {
    "core-api"   = aws_ecs_task_definition.core_api.arn
    "ai-service" = aws_ecs_task_definition.ai_service.arn
    "web"        = aws_ecs_task_definition.web.arn
  }
  security_groups = {
    "core-api"   = aws_security_group.core_api.id
    "ai-service" = aws_security_group.ai_service.id
    "web"        = aws_security_group.web.id
  }
  target_groups = {
    "core-api" = { arn = aws_lb_target_group.core.arn, port = 8080 }
    "web"      = { arn = aws_lb_target_group.web.arn, port = 8080 }
  }
}

resource "aws_ecs_service" "this" {
  for_each               = local.services
  name                   = each.key
  cluster                = aws_ecs_cluster.this.id
  task_definition        = local.task_definitions[each.key]
  desired_count          = var.desired_count
  launch_type            = "FARGATE"
  enable_execute_command = false
  propagate_tags         = "SERVICE"

  network_configuration {
    subnets          = var.app_subnet_ids
    security_groups  = [local.security_groups[each.key]]
    assign_public_ip = false
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  dynamic "load_balancer" {
    for_each = contains(keys(local.target_groups), each.key) ? [local.target_groups[each.key]] : []
    content {
      target_group_arn = load_balancer.value.arn
      container_name   = each.key
      container_port   = load_balancer.value.port
    }
  }

  dynamic "service_registries" {
    for_each = each.key == "ai-service" ? [aws_service_discovery_service.ai.arn] : []
    content {
      registry_arn = service_registries.value
    }
  }

  depends_on = [aws_lb_listener.https]
}
