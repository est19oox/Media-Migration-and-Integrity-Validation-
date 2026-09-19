data "aws_vpc" "selected" {
  id = var.vpc_id
}

resource "aws_security_group" "web" {
  name        = "media-migration-web"
  description = "Security group for Media Migration web servers"
  vpc_id      = data.aws_vpc.selected.id

  ingress {
    description = "SSH from Ansible controller/network"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.allowed_ssh_cidr]
  }

  ingress {
    description = "Transfer service"
    from_port   = 8081
    to_port     = 8081
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "media-migration-web"
  }
}

resource "aws_instance" "web" {
  count = var.web_server_count

  ami                         = var.ami_id
  instance_type               = var.instance_type
  subnet_id                   = var.subnet_id
  key_name                    = var.key_name
  vpc_security_group_ids      = [aws_security_group.web.id]
  associate_public_ip_address = true

  tags = {
    Name        = "media-migration-web-${count.index + 1}"
    Application = "media-migration"
  }
}

resource "local_file" "ansible_inventory" {
  filename = "${path.module}/../ansible/inventory.ini"

  content = templatefile("${path.module}/inventory.ini.tftpl", {
    servers = [
      for i, instance in aws_instance.web : {
        name = "web${i + 1}"
        ip   = instance.public_ip
      }
    ]
  })
}
