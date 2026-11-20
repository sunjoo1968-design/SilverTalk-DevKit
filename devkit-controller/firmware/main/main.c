#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <errno.h>
#include "driver/gpio.h"
#include "driver/i2c_master.h"
#include "driver/uart.h"
#include "esp_wifi.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "esp_random.h"
#include "esp_system.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "mbedtls/md.h"
#include "lwip/sockets.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static i2c_master_dev_handle_t oled;
static const char alphabet[] = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ .:-";
static const uint8_t glyph[][5] = {
{62,81,73,69,62},{0,66,127,64,0},{66,97,81,73,70},{33,65,69,75,49},{24,20,18,127,16},{39,69,69,69,57},{60,74,73,73,48},{1,113,9,5,3},{54,73,73,73,54},{6,73,73,41,30},
{126,17,17,17,126},{127,73,73,73,54},{62,65,65,65,34},{127,65,65,34,28},{127,73,73,73,65},{127,9,9,9,1},{62,65,73,73,122},{127,8,8,8,127},{0,65,127,65,0},{32,64,65,63,1},{127,8,20,34,65},{127,64,64,64,64},{127,2,12,2,127},{127,4,8,16,127},{62,65,65,65,62},{127,9,9,9,6},{62,65,81,33,94},{127,9,25,41,70},{70,73,73,73,49},{1,1,127,1,1},{63,64,64,64,63},{31,32,64,32,31},{63,64,56,64,63},{99,20,8,20,99},{7,8,112,8,7},{97,81,73,69,67},
{0},{0,96,96,0,0},{0,54,54,0,0},{8,8,8,8,8}
};

static void row(unsigned page, const char *label, unsigned bar) {
    if (!oled) return;
    uint8_t address[] = {0, 0x21,0,127,0x22,page,page};
    uint8_t data[129] = {0x40};
    for (unsigned i=0; label[i] && i<21; i++) {
        const char *ch=strchr(alphabet,label[i]);
        if(ch) memcpy(data+1+i*6,glyph[ch-alphabet],5);
    }
    if(bar) memset(data+1,0x7e,bar);
    if(i2c_master_transmit(oled,address,sizeof(address),100)==ESP_OK)
        i2c_master_transmit(oled,data,sizeof(data),100);
}


static void init_oled(void) {
    i2c_master_bus_handle_t bus;
    i2c_master_bus_config_t bus_cfg={.i2c_port=0,.sda_io_num=21,.scl_io_num=22,
        .clk_source=I2C_CLK_SRC_DEFAULT,.glitch_ignore_cnt=7,.flags.enable_internal_pullup=true};
    ESP_ERROR_CHECK(i2c_new_master_bus(&bus_cfg,&bus));
    if(i2c_master_probe(bus,0x3c,100)==ESP_OK) {
        i2c_device_config_t dev={.dev_addr_length=I2C_ADDR_BIT_LEN_7,.device_address=0x3c,.scl_speed_hz=100000};
        ESP_ERROR_CHECK(i2c_master_bus_add_device(bus,&dev,&oled));
        const uint8_t init[]={0,0xae,0xd5,0x80,0xa8,63,0xd3,0,0x40,0x8d,0x14,0x20,0,0xa1,0xc8,0xda,0x12,0x81,0x7f,0xd9,0xf1,0xdb,0x40,0xa4,0xa6,0xaf};
        ESP_ERROR_CHECK(i2c_master_transmit(oled,init,sizeof(init),100));
        for(unsigned p=0;p<8;p++) row(p,"",0);
        row(0,"SILVERTALK",0);
        row(7,"SUNJOOAN V1.8",0);
        puts("OLED 0x3C OK");
    } else puts("OLED 0x3C NOT FOUND");


}

#define PORT 41726
#define LINE_SIZE 256
static esp_netif_t *station;
static unsigned boot_id;
static atomic_uint clicks, long_presses, continuous_presses, selection, talk, mode, usb_seen, wifi_seen;
static char wifi_key[33];
static atomic_uint slot_services=(2U<<4)|1U;
static bool reconnect_wifi;

static bool recent(unsigned at) {
    return at && (unsigned)(xTaskGetTickCount() - at) < pdMS_TO_TICKS(1600);
}

static void mac(const char *message, char output[65]) {
    uint8_t hash[32];
    mbedtls_md_hmac(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256),
        (const uint8_t *)wifi_key, strlen(wifi_key),
        (const uint8_t *)message, strlen(message), hash);
    for (unsigned i=0; i<32; ++i) snprintf(output+i*2, 3, "%02x", hash[i]);
}

static int decode_hex(const char *hex, char *output, size_t capacity) {
    if (!strcmp(hex, "-")) { output[0]=0; return 0; }
    size_t length=strlen(hex);
    if (length%2 || length/2>=capacity || strspn(hex,"0123456789abcdef")!=length) return -1;
    for (size_t i=0; i<length/2; ++i) {
        char byte[3]={hex[2*i],hex[2*i+1],0};
        output[i]=(char)strtoul(byte,NULL,16);
        if (!output[i]) return -1;
    }
    output[length/2]=0;
    return length/2;
}

static void wifi_event(void *arg, esp_event_base_t base, int32_t id, void *data) {
    if (id==WIFI_EVENT_SCAN_DONE) {
        wifi_ap_record_t *aps=calloc(24,sizeof(*aps));
        uint16_t count=24;
        if (!aps || esp_wifi_scan_get_ap_records(&count,aps)!=ESP_OK) count=0;
        for (unsigned i=0;i<count;i++) {
            char line[72]="AP ";
            size_t size=strnlen((char *)aps[i].ssid,32);
            if (!size) continue;
            for (size_t j=0;j<size;j++) snprintf(line+3+j*2,3,"%02x",aps[i].ssid[j]);
            strcat(line,"\n");
            uart_write_bytes(UART_NUM_0,line,strlen(line));
        }
        free(aps);
        uart_write_bytes(UART_NUM_0,"SCAN END\n",9);
    }
    if (reconnect_wifi && (id==WIFI_EVENT_STA_START || id==WIFI_EVENT_STA_DISCONNECTED)) esp_wifi_connect();
}

static bool configure(const char *ssid, const char *password, const char *key) {
    char name[33], pass[64];
    if (decode_hex(ssid,name,sizeof(name))<=0 || decode_hex(password,pass,sizeof(pass))<0 ||
        strlen(key)!=32 || strspn(key,"0123456789abcdef")!=32) return false;
    nvs_handle_t handle;
    if (nvs_open("st_controller",NVS_READWRITE,&handle)!=ESP_OK) return false;
    esp_err_t status=nvs_set_str(handle,"ssid",name);
    if (status==ESP_OK) status=nvs_set_str(handle,"pass",pass);
    if (status==ESP_OK) status=nvs_set_str(handle,"key",key);
    if (status==ESP_OK) status=nvs_commit(handle);
    nvs_close(handle);
    return status==ESP_OK;
}

static void respond(char *request, char *response, bool usb) {
    response[0]=0;
    if (usb && !strcmp(request,"SCAN")) {
        if (esp_wifi_scan_start(NULL,false)==ESP_OK) strcpy(response,"SCAN START");
        else strcpy(response,"SCAN BUSY");
        return;
    }
    if (usb && !strncmp(request,"CFG ",4)) {
        char ssid[65], pass[127], key[33];
        bool ok=sscanf(request+4,"%64s %126s %32s",ssid,pass,key)==3 && configure(ssid,pass,key);
        strcpy(response,ok?"OK REBOOT":"ERR CONFIG");
        return;
    }
    if (!usb) {
        char *signature=strrchr(request,' ');
        if (!wifi_key[0] || !signature || strlen(signature+1)!=64) return;
        *signature++=0;
        char expected[65];
        mac(request,expected);
        unsigned difference=0;
        for (unsigned i=0;i<64;++i) difference|=expected[i]^signature[i];
        if (difference) return;
    }
    char nonce[25];
    unsigned selected, audio_mode;
    unsigned protocol=1,a=0,b=0;
    if (!usb && sscanf(request,"D %24[0-9a-f]",nonce)==1 && strlen(nonce)==24) {
        char signature[65];
        snprintf(response,LINE_SIZE,"HERE %s",nonce);
        mac(response,signature);
        strcat(response," ");
        strcat(response,signature);
        return;
    }
    int fields=sscanf(request,"P %24[0-9a-f] %u %u %u %u %u",nonce,&selected,&audio_mode,&protocol,&a,&b);
    if (fields<3 ||
        selected>2 || audio_mode>5) return;
    if (protocol>=3) {
        if (fields!=6 || a<1 || a>4 || b<1 || b>4) return;
        atomic_store(&slot_services,a|(b<<4));
    }
    atomic_store(usb?&usb_seen:&wifi_seen,xTaskGetTickCount());
    atomic_store(&mode,audio_mode);
    snprintf(response,LINE_SIZE,"S %s %u %u %u %u",nonce,boot_id,
        atomic_load(&clicks),atomic_load(&selection),atomic_load(&talk));
    if (protocol>=2) {
        size_t used=strlen(response);
        snprintf(response+used,LINE_SIZE-used," %u",atomic_load(&long_presses));
    }
    if (protocol>=4) {
        size_t used=strlen(response);
        snprintf(response+used,LINE_SIZE-used," %u",atomic_load(&continuous_presses));
    }
    if (!usb) {
        char signature[65];
        mac(response,signature);
        strcat(response," ");
        strcat(response,signature);
    }
}

static void wifi_worker(void *arg) {
    int server=socket(AF_INET,SOCK_STREAM,0);
    struct sockaddr_in address={.sin_family=AF_INET,.sin_port=htons(PORT),.sin_addr.s_addr=htonl(INADDR_ANY)};
    if (server<0 || bind(server,(struct sockaddr *)&address,sizeof(address))<0 || listen(server,2)<0) {
        if (server>=0) close(server);
        vTaskDelete(NULL);
        return;
    }
    for (;;) {
        int peer=accept(server,NULL,NULL);
        if (peer<0) { vTaskDelay(pdMS_TO_TICKS(100)); continue; }
        struct timeval timeout={.tv_usec=250000};
        setsockopt(peer,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
        setsockopt(peer,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
        char request[LINE_SIZE]={0}, response[LINE_SIZE];
        size_t used=0;
        while (used<sizeof(request)-1 && recv(peer,request+used,1,0)==1 && request[used]!='\n') ++used;
        if (used<sizeof(request)-1 && request[used]=='\n') {
            request[used]=0;
            respond(request,response,false);
            if (response[0]) {
                strcat(response,"\n");
                send(peer,response,strlen(response),0);
            }
        }
        close(peer);
    }
}

static void discover_worker(void *arg) {
    int server=socket(AF_INET,SOCK_DGRAM,0);
    struct sockaddr_in address={.sin_family=AF_INET,.sin_port=htons(PORT),.sin_addr.s_addr=htonl(INADDR_ANY)};
    if (server<0 || bind(server,(struct sockaddr *)&address,sizeof(address))<0) {
        if (server>=0) close(server);
        vTaskDelete(NULL);
        return;
    }
    for (;;) {
        struct sockaddr_in peer;
        socklen_t length=sizeof(peer);
        char request[LINE_SIZE],response[LINE_SIZE];
        int size=recvfrom(server,request,sizeof(request)-1,0,(struct sockaddr *)&peer,&length);
        if (size<=0) {vTaskDelay(pdMS_TO_TICKS(50));continue;}
        request[size]=0;
        if (strncmp(request,"D ",2)) continue;
        respond(request,response,false);
        if (response[0]) sendto(server,response,strlen(response),0,(struct sockaddr *)&peer,length);
    }
}

void app_main(void) {
    ESP_ERROR_CHECK(nvs_flash_init());
    boot_id=esp_random();
    gpio_config_t inputs={.pin_bit_mask=(1ULL<<18)|(1ULL<<23)|(1ULL<<25)|(1ULL<<33),.mode=GPIO_MODE_INPUT,.pull_up_en=GPIO_PULLUP_ENABLE};
    ESP_ERROR_CHECK(gpio_config(&inputs));
    gpio_config_t leds={.pin_bit_mask=(1ULL<<19)|(1ULL<<26)|(1ULL<<27)|(1ULL<<32)|(1ULL<<13),.mode=GPIO_MODE_OUTPUT};
    ESP_ERROR_CHECK(gpio_config(&leds));
    uart_config_t uart={.baud_rate=115200,.data_bits=UART_DATA_8_BITS,.parity=UART_PARITY_DISABLE,
        .stop_bits=UART_STOP_BITS_1,.flow_ctrl=UART_HW_FLOWCTRL_DISABLE,.source_clk=UART_SCLK_DEFAULT};
    ESP_ERROR_CHECK(uart_param_config(UART_NUM_0,&uart));
    ESP_ERROR_CHECK(uart_driver_install(UART_NUM_0,1024,0,0,NULL,0));
    init_oled();
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    station=esp_netif_create_default_wifi_sta();
    nvs_handle_t handle;
    char ssid[33]={0},pass[64]={0};
    if (nvs_open("st_controller",NVS_READONLY,&handle)==ESP_OK) {
        size_t size=sizeof(ssid); nvs_get_str(handle,"ssid",ssid,&size);
        size=sizeof(pass); nvs_get_str(handle,"pass",pass,&size);
        size=sizeof(wifi_key); nvs_get_str(handle,"key",wifi_key,&size);
        nvs_close(handle);
    }
    reconnect_wifi=ssid[0] && wifi_key[0];
    {
        wifi_init_config_t init=WIFI_INIT_CONFIG_DEFAULT();
        ESP_ERROR_CHECK(esp_wifi_init(&init));
        ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));
        ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT,ESP_EVENT_ANY_ID,wifi_event,NULL));
        wifi_config_t config={0};
        memcpy(config.sta.ssid,ssid,strlen(ssid));
        memcpy(config.sta.password,pass,strlen(pass));
        ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
        ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA,&config));
        ESP_ERROR_CHECK(esp_wifi_start());
        if (reconnect_wifi) {
            xTaskCreate(wifi_worker,"control_wifi",4096,NULL,4,NULL);
            xTaskCreate(discover_worker,"find_box",4096,NULL,4,NULL);
        }
    }

    enum { SERVICE_PIN, STANDBY_PIN, PTT_PIN, CONTINUOUS_PIN, BUTTON_COUNT };
    const gpio_num_t pins[BUTTON_COUNT]={18,23,25,33};
    unsigned stable[BUTTON_COUNT], history[BUTTON_COUNT];
    for (unsigned i=0;i<BUTTON_COUNT;++i) {
        stable[i]=gpio_get_level(pins[i]);
        history[i]=stable[i]?15:0;
    }
    atomic_store(&selection,stable[0]?1:2);
    char request[LINE_SIZE]={0}, response[LINE_SIZE];
    size_t used=0;
    unsigned refresh=0;
    unsigned previous_display=0;
    TickType_t control_pressed_at=0;
    bool control_held=false;
    for (;;) {
        for (unsigned i=0;i<BUTTON_COUNT;++i) {
            history[i]=((history[i]<<1)|gpio_get_level(pins[i]))&15;
            if (history[i]!=0 && history[i]!=15) continue;
            unsigned level=history[i]==15;
            if (level==stable[i]) continue;
            stable[i]=level;
            if (i==SERVICE_PIN) atomic_store(&selection,level?1:2);
            if (i==STANDBY_PIN) {
                if (!level) {
                    control_pressed_at=xTaskGetTickCount();
                    control_held=true;
                } else if (control_held) {
                    if (xTaskGetTickCount()-control_pressed_at>=pdMS_TO_TICKS(3000))
                        atomic_fetch_add(&long_presses,1);
                    else
                        atomic_fetch_add(&clicks,1);
                    control_held=false;
                }
            }
            if (i==PTT_PIN) atomic_store(&talk,!level);
            if (i==CONTINUOUS_PIN && !level) atomic_fetch_add(&continuous_presses,1);
        }
        if (control_held && xTaskGetTickCount()-control_pressed_at>=pdMS_TO_TICKS(3000)) {
            control_held=false;
            atomic_fetch_add(&long_presses,1);
        }
        uint8_t byte;
        while (uart_read_bytes(UART_NUM_0,&byte,1,0)==1) {
            if (byte=='\r') continue;
            if (byte=='\n') {
                if (used>=sizeof(request)) {used=0;continue;}
                request[used]=0;
                respond(request,response,true);
                if (response[0]) {
                    uart_write_bytes(UART_NUM_0,response,strlen(response));
                    uart_write_bytes(UART_NUM_0,"\n",1);
                    if (!strcmp(response,"OK REBOOT")) {
                        uart_wait_tx_done(UART_NUM_0,pdMS_TO_TICKS(200));
                        esp_restart();
                    }
                }
                used=0;
            } else if (used<sizeof(request)-1) request[used++]=byte;
            else used=sizeof(request);
        }
        bool wired=recent(atomic_load(&usb_seen)), wireless=recent(atomic_load(&wifi_seen));
        unsigned state=atomic_load(&mode);
        bool linked=wired||wireless;
        bool flash_fast=(xTaskGetTickCount()/pdMS_TO_TICKS(225))&1;
        gpio_set_level(GPIO_NUM_19,linked&&(state==5||(state==3&&flash_fast)));
        gpio_set_level(GPIO_NUM_13,linked&&state==5);
        gpio_set_level(GPIO_NUM_26,linked&&(state==2||state==3||state==5||(state==4&&((xTaskGetTickCount()/pdMS_TO_TICKS(400))&1))));
        gpio_set_level(GPIO_NUM_27,atomic_load(&selection)==1);
        gpio_set_level(GPIO_NUM_32,atomic_load(&selection)==2);
        if (++refresh==20) {
            refresh=0;
            esp_netif_ip_info_t ip={0};
            esp_netif_get_ip_info(station,&ip);
            char address[24]="NO WIFI IP";
            if (ip.ip.addr) snprintf(address,sizeof(address),IPSTR,IP2STR(&ip.ip));
            static const char *services[]={"","SESAME","GEMINI LIVE","CHATGPT","PI"};
            unsigned pair=atomic_load(&slot_services), slot=atomic_load(&selection);
            unsigned service=slot==1?pair&15:(pair>>4)&15;
            char title[24];
            snprintf(title,sizeof(title),"%c %s",slot==1?'A':'B',services[service]);
            row(2,title,0);
            unsigned display=(slot<<4)|service;
            if (display!=previous_display) {
                printf("OLED SERVICE %s\n",title);
                previous_display=display;
            }
            row(3,wired?"USB CONNECTED":wireless?"WIFI CONNECTED":"PHONE WAIT",0);
            row(4,address,0);
            const char *states[]={"STANDBY","CALL","MIC MUTED","MIC OPEN","READY","MIC ON CONTINUOUS"};
            row(5,wired||wireless?states[atomic_load(&mode)]:"CHECK PHONE",0);
        }
        vTaskDelay(pdMS_TO_TICKS(10));
    }
}








