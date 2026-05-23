resource "random_password" "db" {
  length  = 32
  special = false
}

resource "aws_db_subnet_group" "temporal" {
  name       = "${var.cluster_name}-db"
  subnet_ids = module.vpc.private_subnets
}

resource "aws_security_group" "db" {
  name   = "${var.cluster_name}-db"
  vpc_id = module.vpc.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "db_from_nodes" {
  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = module.eks.node_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_db_instance" "temporal" {
  identifier     = "${var.cluster_name}-db"
  engine         = "postgres"
  engine_version = "16"
  instance_class = "db.t4g.micro"

  allocated_storage     = 20
  max_allocated_storage = 100
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = "temporal"
  username = "temporal"
  password = random_password.db.result

  db_subnet_group_name   = aws_db_subnet_group.temporal.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period = 7
  skip_final_snapshot     = true
  apply_immediately       = true
}
