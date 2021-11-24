ifndef scada_dir
scada_dir := .
endif

build:
	@echo "build containers"

container_setup:
	@echo "Setup contaienr for $(target)"
	ln -f $(scada_dir)/docker-compose-$(target).yml $(scada_dir)/docker-compose.yml
