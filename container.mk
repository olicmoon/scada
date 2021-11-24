build:
	@echo "build containers"

container_setup:
	@echo "Setup contaienr for $(target)"
	@ln -sf docker-compose-$(target).xml docker-compose.xml
